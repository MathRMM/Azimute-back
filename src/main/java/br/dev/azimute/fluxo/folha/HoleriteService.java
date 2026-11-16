package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.Uuid7;
import br.dev.azimute.contas.ContasFacade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Processamento do holerite (RF32, RF33, RF35b, RF38, RN10–RN15, RN19). O leitor de PDF e o lançamento manual
 * entregam o mesmo {@link HoleriteLido}; daqui em diante o caminho é um só.
 * <p>
 * Os efeitos são reaplicáveis (T6): {@link #aplicar} compara o que o holerite deve gerar com o que existe e só mexe
 * na diferença, pela fachada de {@code contas}, na mesma transação (RN04).
 */
@Service
class HoleriteService {

    /** O holerite com as rubricas e o catálogo usado para interpretá-las. */
    record Detalhe(Holerite holerite, List<RubricaLida> rubricas, Map<String, RubricaCatalogo> catalogo) {

        /** Rubricas do holerite ainda a classificar (RF36), uma vez cada. */
        List<RubricaCatalogo> avisos() {
            var vistos = new HashSet<String>();
            return rubricas.stream().map(r -> catalogo.get(r.codigo()))
                    .filter(c -> c != null && c.aClassificar() && vistos.add(c.codigo()))
                    .toList();
        }
    }

    /** O holerite e se este envio o criou (201) ou é um reenvio (200). */
    record Resultado(Detalhe detalhe, boolean criado) {}

    /** O PDF original e a competência, para nomear o arquivo baixado (RF37). */
    record Arquivo(Competencia competencia, byte[] conteudo) {}

    private final HoleriteRepository holerites;
    private final CatalogoRepository catalogo;
    private final EfeitosRepository efeitos;
    private final AdiantamentoRepository adiantamentos;
    private final ConfiguracaoFolhaService configuracao;
    private final ContrapartidaService contrapartidas;
    private final ContasFacade contas;
    private final LeitorDeHolerite leitor;

    HoleriteService(HoleriteRepository holerites, CatalogoRepository catalogo, EfeitosRepository efeitos,
                    AdiantamentoRepository adiantamentos, ConfiguracaoFolhaService configuracao,
                    ContrapartidaService contrapartidas, ContasFacade contas, LeitorDeHolerite leitor) {
        this.leitor = leitor;
        this.holerites = holerites;
        this.catalogo = catalogo;
        this.efeitos = efeitos;
        this.adiantamentos = adiantamentos;
        this.configuracao = configuracao;
        this.contrapartidas = contrapartidas;
        this.contas = contas;
    }

    /** RF32: o holerite em JSON. O "hash" é o do conteúdo canônico, com as datas como foram informadas (T9). */
    @Transactional
    Resultado importarManual(HoleriteLido lido, LocalDate dataAdiantamento, LocalDate dataLiquido) {
        return importar(lido, dataAdiantamento, dataLiquido, Holerite.Origem.MANUAL,
                hash(canonico(lido, dataAdiantamento, dataLiquido)));
    }

    /**
     * RF31: lê o PDF e segue o mesmo caminho do manual. O hash é o do arquivo (T9); o PDF é guardado à parte (T8).
     * As datas informadas não entram no hash: o mesmo PDF reenviado é o mesmo holerite.
     */
    @Transactional
    Resultado importarPdf(byte[] pdf, LocalDate dataAdiantamento, LocalDate dataLiquido) {
        var resultado = importar(leitor.ler(pdf), dataAdiantamento, dataLiquido, Holerite.Origem.PDF, hash(pdf));
        if (resultado.criado()) {
            holerites.salvarArquivo(resultado.detalhe().holerite().id(), pdf);
        }
        return resultado;
    }

    /** RF37: um holerite vivo, com as rubricas. */
    Detalhe buscar(HoleriteId id) {
        return detalhe(vivo(id));
    }

    List<Holerite> listar(int ano) {
        return holerites.listar(ano);
    }

    /** RF37: o PDF original; holerite lançado à mão não tem. */
    Arquivo pdf(HoleriteId id) {
        var holerite = vivo(id);
        return holerites.arquivo(id).map(conteudo -> new Arquivo(holerite.competencia(), conteudo))
                .orElseThrow(() -> new PdfNaoEncontradoException(id));
    }

    /**
     * Confere (RN10), recusa o repetido (RN11, T9), registra no catálogo as rubricas novas (RF36), grava a
     * contrapartida pela regra vigente (RN19) e aplica os efeitos.
     */
    Resultado importar(HoleriteLido lido, LocalDate dataAdiantamento, LocalDate dataLiquido, Holerite.Origem origem,
                       String hash) {
        ConferenciaDeHolerite.conferir(lido);
        configuracao.exigir();
        var mesmo = holerites.buscarPorHash(hash);
        if (mesmo.isPresent()) {
            if (mesmo.get().excluido()) {
                throw new HoleriteExcluidoException(mesmo.get().holerite().id());
            }
            return new Resultado(detalhe(mesmo.get().holerite()), false);
        }
        var competencia = lido.competencia();
        if (holerites.existeVivo(competencia)) {
            throw new HoleriteJaImportadoException(competencia);
        }

        for (var rubrica : lido.rubricas()) {
            catalogo.inserirAClassificar(rubrica.codigo(), rubrica.descricao(), rubrica.vencimento() != null
                    ? NaturezaRubrica.PROVENTO : NaturezaRubrica.OUTRO_DESCONTO);
        }
        var id = new HoleriteId(Uuid7.novo());
        // Sem data informada, o adiantamento lançado antes dá a data; sem ele, vale a padrão (RN18, RF34).
        var antecipado = adiantamentos.antecipado(competencia, EfeitosDoHolerite.referencia(id, "GANHO"));
        var adiantamento = dataAdiantamento != null ? dataAdiantamento
                : antecipado.map(AdiantamentoRepository.Adiantamento::data)
                .orElseGet(() -> DatasDoSalario.adiantamento(competencia));
        var basica = EfeitosDoHolerite.soma(NaturezaRubrica.PREVIDENCIA_BASICA, lido.rubricas(), catalogo.mapa());
        var holerite = new Holerite(id, competencia, origem, hash, lido.totalVencimentos(), lido.totalDescontos(),
                lido.liquido(), lido.bases(), contrapartidas.contrapartida(competencia, basica), adiantamento,
                DatasDoSalario.liquidoOu(dataLiquido, competencia));
        holerites.inserir(holerite, lido.rubricas());
        aplicar(holerite);
        return new Resultado(detalhe(holerite), true);
    }

    /** RF38: o holerite e tudo o que ele gerou saem dos saldos; o adiantamento lançado antes volta a ser solto. */
    @Transactional
    void excluir(HoleriteId id) {
        var registro = holerites.buscar(id);
        if (registro.isEmpty() || registro.get().excluido()) {
            return;
        }
        holerites.excluir(id);
        efeitos.gastosVivos(id).forEach(efeitos::excluirGasto);
        for (var lancamento : efeitos.lancamentosVivos(id)) {
            efeitos.excluirLancamento(lancamento.id());
            contas.excluirLancamento(lancamento.id());
        }
        var ganho = EfeitosDoHolerite.referencia(id, "GANHO");
        efeitos.recebimentosVivos(ganho).forEach(this::retirar);
        efeitos.excluirGanho(ganho);
    }

    /** RF38: desfaz a exclusão e reaplica os efeitos. Outro holerite vivo na competência impede (T9). */
    @Transactional
    Detalhe restaurar(HoleriteId id) {
        var registro = holerites.buscar(id).orElseThrow(() -> new HoleriteNaoEncontradoException(id));
        if (registro.excluido()) {
            holerites.restaurar(registro.holerite());
            aplicar(registro.holerite());
        }
        return detalhe(registro.holerite());
    }

    /** RF35b: corrige a contrapartida de um holerite; a entrada na previdência acompanha, e só a dele. */
    @Transactional
    Detalhe corrigirContrapartida(HoleriteId id, Dinheiro contrapartida) {
        var atual = vivo(id);
        holerites.alterarContrapartida(id, contrapartida);
        var corrigido = new Holerite(id, atual.competencia(), atual.origem(), atual.hash(), atual.totalVencimentos(),
                atual.totalDescontos(), atual.liquido(), atual.bases(), contrapartida, atual.dataAdiantamento(),
                atual.dataLiquido());
        aplicar(corrigido);
        return detalhe(corrigido);
    }

    /** RN13: reclassificar uma rubrica refaz os efeitos dos holerites vivos que a usam. */
    void reaplicarComRubrica(String codigo) {
        holerites.vivosComRubrica(codigo).forEach(id -> aplicar(vivo(id)));
    }

    /** Leva os efeitos gravados ao que o holerite deve gerar hoje, com o catálogo atual (T6, RN13). */
    private void aplicar(Holerite holerite) {
        var config = configuracao.exigir();
        var rubricas = holerites.rubricas(holerite.id());
        var ganho = EfeitosDoHolerite.referencia(holerite.id(), "GANHO");
        var antecipado = adiantamentos.antecipado(holerite.competencia(), ganho)
                .map(AdiantamentoRepository.Adiantamento::id).orElse(null);
        var esperado = EfeitosDoHolerite.calcular(holerite, rubricas, catalogo.mapa(), config, antecipado);

        efeitos.salvarGanho(ganho, holerite.competencia(), esperado.ganho().bruto(), esperado.ganho().liquido(),
                holerite.id());
        var recebimentos = new HashSet<UUID>();
        for (var r : esperado.recebimentos()) {
            recebimentos.add(r.id());
            var existente = efeitos.recebimento(r.id());
            if (existente.isEmpty()) {
                contas.registrarEntrada(r.conta(), r.id(), r.valor(), r.data());
                efeitos.inserirRecebimento(r.id(), ganho, holerite.competencia(), r.papel(), r.valor(), r.data(),
                        r.conta());
            } else {
                ajustarNaConta(existente.get(), r.valor(), r.data(), r.conta());
                efeitos.atualizarRecebimento(r.id(), ganho, r.valor(), r.data(), r.conta());
            }
        }
        efeitos.recebimentosVivos(ganho).stream().filter(r -> !recebimentos.contains(r.id())).forEach(this::retirar);

        var lancamentos = new HashSet<UUID>();
        for (var l : esperado.lancamentos()) {
            lancamentos.add(l.id());
            var existente = efeitos.lancamento(l.id());
            if (existente.isEmpty()) {
                contas.registrarEntrada(l.conta(), l.id(), l.valor(), l.data());
                efeitos.inserirLancamento(l.id(), holerite.id(), l.papel(), l.valor(), l.data(), l.conta());
            } else {
                ajustarNaConta(existente.get(), l.valor(), l.data(), l.conta());
                efeitos.atualizarLancamento(l.id(), l.valor(), l.data(), l.conta());
            }
        }
        for (var vivo : efeitos.lancamentosVivos(holerite.id())) {
            if (!lancamentos.contains(vivo.id())) {
                efeitos.excluirLancamento(vivo.id());
                contas.excluirLancamento(vivo.id());
            }
        }

        // Gasto em folha não mexe em conta: o dinheiro nem chegou a ela.
        var gastos = new HashSet<UUID>();
        for (var g : esperado.gastos()) {
            gastos.add(g.id());
            if (efeitos.gasto(g.id()).isEmpty()) {
                efeitos.inserirGasto(g.id(), holerite.id(), g.descricao(), g.valor(), g.data(), g.categoria());
            } else {
                efeitos.atualizarGasto(g.id(), g.descricao(), g.valor(), g.data(), g.categoria());
            }
        }
        efeitos.gastosVivos(holerite.id()).stream().filter(g -> !gastos.contains(g)).forEach(efeitos::excluirGasto);
    }

    /** Linha que já existia: traz de volta da exclusão e acerta valor, data e conta pela fachada. */
    private void ajustarNaConta(EfeitosRepository.Linha existente, Dinheiro valor, LocalDate data,
                                br.dev.azimute.contas.ContaId conta) {
        if (existente.excluida()) {
            contas.restaurarLancamento(existente.id());
        }
        if (existente.excluida() || !existente.mesmosValores(valor, data, conta)) {
            contas.alterarLancamento(existente.id(), conta, valor, data);
        }
    }

    /** Recebimento que o holerite não gera mais: o antecipado é solto; os outros são excluídos com a entrada. */
    private void retirar(EfeitosRepository.Linha recebimento) {
        if (recebimento.lancadoAntes()) {
            efeitos.soltarRecebimento(recebimento.id());
        } else {
            efeitos.excluirRecebimento(recebimento.id());
            contas.excluirLancamento(recebimento.id());
        }
    }

    private Holerite vivo(HoleriteId id) {
        return holerites.buscar(id).filter(r -> !r.excluido()).map(HoleriteRepository.Registro::holerite)
                .orElseThrow(() -> new HoleriteNaoEncontradoException(id));
    }

    private Detalhe detalhe(Holerite holerite) {
        return new Detalhe(holerite, holerites.rubricas(holerite.id()), catalogo.mapa());
    }

    /** O conteúdo do holerite manual numa forma única: o mesmo JSON, com ou sem espaços, dá o mesmo hash. */
    private static String canonico(HoleriteLido lido, LocalDate dataAdiantamento, LocalDate dataLiquido) {
        var texto = new StringBuilder("MANUAL|").append(lido.competencia());
        for (var r : lido.rubricas()) {
            texto.append('|').append(r.codigo()).append(';').append(r.descricao()).append(';')
                    .append(r.referencia() == null ? "" : r.referencia().stripTrailingZeros().toPlainString())
                    .append(';').append(r.vencimento()).append(';').append(r.desconto());
        }
        var b = lido.bases();
        texto.append('|').append(lido.totalVencimentos()).append(';').append(lido.totalDescontos()).append(';')
                .append(lido.liquido()).append('|').append(b.salarioBase()).append(';').append(b.baseInss())
                .append(';').append(b.baseFgts()).append(';').append(b.fgtsMes()).append(';').append(b.baseIrrf())
                .append('|').append(dataAdiantamento).append(';').append(dataLiquido);
        return texto.toString();
    }

    static String hash(String conteudo) {
        return hash(conteudo.getBytes(StandardCharsets.UTF_8));
    }

    static String hash(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
