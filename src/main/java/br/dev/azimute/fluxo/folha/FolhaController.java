package br.dev.azimute.fluxo.folha;

import br.dev.azimute.plataforma.web.ValorMonetario;
import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.LeitorDeCampos;
import br.dev.azimute.comum.Taxa;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Folha de pagamento (RF30, RF32, RF34, RF35, RF35a, RF35b, RF38). Exige token, como tudo sob {@code /api} (SEC01).
 * <p>
 * A reclassificação de rubrica recebe o código como parâmetro ({@code ?codigo=}), e não no caminho: códigos como
 * {@code /507} têm barra, e barra codificada no caminho é recusada pelo servidor.
 */
@RestController
@RequestMapping("/api/folha")
class FolhaController {

    private static final String MES = "\\d{4}-(0[1-9]|1[0-2])";
    private static final String FORMATO_MES = "deve estar no formato AAAA-MM";
    private static final String CONTRAPARTIDA = "deve ser um valor de zero para cima com até 2 casas decimais";
    static final long LIMITE_DO_PDF = 2L * 1024 * 1024;
    private static final byte[] ASSINATURA = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    record ConfiguracaoRequest(@NotNull UUID contaSalario, @NotNull UUID contaFgts, @NotNull UUID contaPrevidencia) {}

    record ConfiguracaoResponse(UUID contaSalario, UUID contaFgts, UUID contaPrevidencia) {
        static ConfiguracaoResponse de(ConfiguracaoFolha c) {
            return new ConfiguracaoResponse(c.salario().valor(), c.fgts().valor(), c.previdencia().valor());
        }
    }

    /** Uma linha do holerite: vencimento ou desconto, nunca os dois (como no papel). */
    record RubricaRequest(
            @NotBlank String codigo,
            @NotBlank String descricao,
            @ValorMonetario BigDecimal referencia,
            @ValorMonetario.NaoNegativo BigDecimal vencimento,
            @ValorMonetario.NaoNegativo BigDecimal desconto) {

        @AssertTrue(message = "informe vencimento ou desconto, só um")
        boolean isValorUnico() {
            return (vencimento == null) != (desconto == null);
        }

        RubricaLida paraDominio() {
            return new RubricaLida(codigo.strip(), descricao.strip(), referencia, dinheiro(vencimento),
                    dinheiro(desconto));
        }
    }

    record BasesRequest(
            @ValorMonetario.NaoNegativo BigDecimal salarioBase,
            @ValorMonetario.NaoNegativo BigDecimal baseInss,
            @ValorMonetario.NaoNegativo BigDecimal baseFgts,
            @ValorMonetario.NaoNegativo BigDecimal fgtsMes,
            @ValorMonetario.NaoNegativo BigDecimal baseIrrf) {

        BasesLidas paraDominio() {
            return new BasesLidas(dinheiro(salarioBase), dinheiro(baseInss), dinheiro(baseFgts), dinheiro(fgtsMes),
                    dinheiro(baseIrrf));
        }
    }

    /** RF32: o mesmo conteúdo do PDF, em JSON. Sem as datas, valem as padrão (RN18). */
    record HoleriteManualRequest(
            @NotNull @Pattern(regexp = MES, message = FORMATO_MES) String competencia,
            @NotEmpty List<@Valid @NotNull RubricaRequest> rubricas,
            @NotNull @ValorMonetario.NaoNegativo BigDecimal totalVencimentos,
            @NotNull @ValorMonetario.NaoNegativo BigDecimal totalDescontos,
            @NotNull @ValorMonetario.NaoNegativo BigDecimal liquido,
            @NotNull @Valid BasesRequest bases,
            LocalDate dataAdiantamento,
            LocalDate dataLiquido) {

        HoleriteLido paraDominio() {
            return new HoleriteLido(Competencia.de(competencia),
                    rubricas.stream().map(RubricaRequest::paraDominio).toList(), new Dinheiro(totalVencimentos),
                    new Dinheiro(totalDescontos), new Dinheiro(liquido), bases.paraDominio());
        }
    }

    record RubricaResponse(String codigo, String descricao, BigDecimal referencia, Dinheiro vencimento,
                           Dinheiro desconto) {
        static RubricaResponse de(RubricaLida r) {
            return new RubricaResponse(r.codigo(), r.descricao(), r.referencia(), r.vencimento(), r.desconto());
        }
    }

    record Impostos(Dinheiro inss, Dinheiro irrf, Dinheiro total) {}

    /** RN19: básica e voluntária como vieram; a contrapartida gravada na importação. */
    record Previdencia(Dinheiro basica, Dinheiro contrapartida, Dinheiro voluntaria, Dinheiro total) {}

    record Aviso(String codigo, String descricao, NaturezaRubrica natureza) {}

    /** Impostos e previdência calculados na leitura, das rubricas e do catálogo (T10). */
    record HoleriteResponse(UUID id, Competencia competencia, Holerite.Origem origem, Dinheiro totalVencimentos,
                            Dinheiro totalDescontos, Dinheiro liquido, BasesLidas bases, Impostos impostos,
                            Previdencia previdencia, LocalDate dataAdiantamento, LocalDate dataLiquido,
                            List<RubricaResponse> rubricas, List<Aviso> avisos) {

        static HoleriteResponse de(HoleriteService.Detalhe d) {
            var h = d.holerite();
            var inss = EfeitosDoHolerite.soma(NaturezaRubrica.INSS, d.rubricas(), d.catalogo());
            var irrf = EfeitosDoHolerite.soma(NaturezaRubrica.IRRF, d.rubricas(), d.catalogo());
            var basica = EfeitosDoHolerite.soma(NaturezaRubrica.PREVIDENCIA_BASICA, d.rubricas(), d.catalogo());
            var voluntaria = EfeitosDoHolerite.soma(NaturezaRubrica.PREVIDENCIA_VOLUNTARIA, d.rubricas(),
                    d.catalogo());
            var contrapartida = h.contrapartidaPrevidencia();
            return new HoleriteResponse(h.id().valor(), h.competencia(), h.origem(), h.totalVencimentos(),
                    h.totalDescontos(), h.liquido(), h.bases(), new Impostos(inss, irrf, inss.mais(irrf)),
                    new Previdencia(basica, contrapartida, voluntaria, basica.mais(contrapartida).mais(voluntaria)),
                    h.dataAdiantamento(), h.dataLiquido(), d.rubricas().stream().map(RubricaResponse::de).toList(),
                    d.avisos().stream().map(r -> new Aviso(r.codigo(), r.descricao(), r.natureza()))
                            .toList());
        }
    }

    record HoleriteResumoResponse(UUID id, Competencia competencia, Holerite.Origem origem, Dinheiro totalVencimentos,
                                  Dinheiro totalDescontos, Dinheiro liquido, LocalDate dataAdiantamento,
                                  LocalDate dataLiquido) {
        static HoleriteResumoResponse de(Holerite h) {
            return new HoleriteResumoResponse(h.id().valor(), h.competencia(), h.origem(),
                    h.totalVencimentos(), h.totalDescontos(), h.liquido(), h.dataAdiantamento(), h.dataLiquido());
        }
    }

    record AdiantamentoRequest(
            @NotNull @Pattern(regexp = MES, message = FORMATO_MES) String competencia,
            @NotNull @ValorMonetario.Positivo BigDecimal valor,
            LocalDate data) {}

    record AdiantamentoResponse(UUID id, Competencia competencia, Dinheiro valor, LocalDate data, UUID conta) {
        static AdiantamentoResponse de(AdiantamentoRepository.Adiantamento a) {
            return new AdiantamentoResponse(a.id().valor(), a.competencia(), a.valor(), a.data(), a.conta().valor());
        }
    }

    record RubricaCatalogoResponse(String codigo, String descricao, NaturezaRubrica natureza, UUID categoria,
                                   boolean aClassificar) {
        static RubricaCatalogoResponse de(RubricaCatalogo r) {
            return new RubricaCatalogoResponse(r.codigo(), r.descricao(), r.natureza(),
                    r.categoria() == null ? null : r.categoria().valor(), r.aClassificar());
        }
    }

    /** O percentual em pontos percentuais: 100 é a empresa pondo o mesmo valor da básica. */
    record RegraRequest(
            @NotNull @Pattern(regexp = MES, message = FORMATO_MES) String aPartirDe,
            @NotNull @PositiveOrZero @Digits(integer = 5, fraction = 2) BigDecimal percentualDaBasica) {}

    /** Percentual como texto decimal, como o dinheiro: o cliente não lê como {@code Double}. */
    record RegraResponse(Competencia aPartirDe, String percentualDaBasica) {
        static RegraResponse de(RegraContrapartida r) {
            return new RegraResponse(r.aPartirDe(),
                    r.percentualDaBasica().pontos().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
        }
    }

    private final ConfiguracaoFolhaService configuracao;
    private final HoleriteService holerites;
    private final AdiantamentoService adiantamentos;
    private final CatalogoService catalogo;
    private final ContrapartidaService contrapartidas;
    private final ResumoFolhaService resumos;

    FolhaController(ConfiguracaoFolhaService configuracao, HoleriteService holerites,
                    AdiantamentoService adiantamentos, CatalogoService catalogo,
                    ContrapartidaService contrapartidas, ResumoFolhaService resumos) {
        this.resumos = resumos;
        this.configuracao = configuracao;
        this.holerites = holerites;
        this.adiantamentos = adiantamentos;
        this.catalogo = catalogo;
        this.contrapartidas = contrapartidas;
    }

    @PutMapping("/configuracao")
    ConfiguracaoResponse configurar(@Valid @RequestBody ConfiguracaoRequest corpo) {
        return ConfiguracaoResponse.de(configuracao.configurar(new ConfiguracaoFolha(
                new ContaId(corpo.contaSalario()), new ContaId(corpo.contaFgts()),
                new ContaId(corpo.contaPrevidencia()))));
    }

    /**
     * RF31: o PDF do holerite. Assinatura {@code %PDF-} e limite de 2 MB conferidos aqui (SEC04, T12); o limite do
     * {@code multipart} no servidor barra antes os arquivos maiores, com o mesmo 413.
     */
    @PostMapping(path = "/holerites", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<HoleriteResponse> importarPdf(
            @RequestPart MultipartFile arquivo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataAdiantamento,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataLiquido)
            throws IOException {
        if (arquivo.getSize() > LIMITE_DO_PDF) {
            throw new MaxUploadSizeExceededException(LIMITE_DO_PDF);
        }
        var conteudo = arquivo.getBytes();
        if (!Arrays.equals(conteudo, 0, Math.min(conteudo.length, ASSINATURA.length), ASSINATURA, 0,
                ASSINATURA.length)) {
            throw new ArquivoNaoEPdfException();
        }
        var resultado = holerites.importarPdf(conteudo, dataAdiantamento, dataLiquido);
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(HoleriteResponse.de(resultado.detalhe()));
    }

    /** RF37: os holerites vivos do ano, sem as rubricas. */
    @GetMapping("/holerites")
    List<HoleriteResumoResponse> listar(@RequestParam int ano) {
        return holerites.listar(ano).stream().map(HoleriteResumoResponse::de).toList();
    }

    @GetMapping("/holerites/{id}")
    HoleriteResponse buscar(@PathVariable UUID id) {
        return HoleriteResponse.de(holerites.buscar(new HoleriteId(id)));
    }

    /** RF37, SEC05: o PDF original, só com token. */
    @GetMapping("/holerites/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        var arquivo = holerites.pdf(new HoleriteId(id));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("holerite-" + arquivo.competencia() + ".pdf").build().toString())
                .body(arquivo.conteudo());
    }

    /** 201 no primeiro envio; 200 no reenvio do mesmo conteúdo (RN11). */
    @PostMapping("/holerites/manual")
    ResponseEntity<HoleriteResponse> importarManual(@Valid @RequestBody HoleriteManualRequest corpo) {
        var resultado = holerites.importarManual(corpo.paraDominio(), corpo.dataAdiantamento(), corpo.dataLiquido());
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(HoleriteResponse.de(resultado.detalhe()));
    }

    @DeleteMapping("/holerites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excluir(@PathVariable UUID id) {
        holerites.excluir(new HoleriteId(id));
    }

    @PostMapping("/holerites/{id}/restaurar")
    HoleriteResponse restaurar(@PathVariable UUID id) {
        return HoleriteResponse.de(holerites.restaurar(new HoleriteId(id)));
    }

    /** RF35b: corrige a contrapartida gravada de um holerite (zero ou mais, até 2 casas). */
    @PatchMapping("/holerites/{id}/previdencia")
    HoleriteResponse corrigirPrevidencia(@PathVariable UUID id, @RequestBody Map<String, Object> corpo) {
        var leitor = new LeitorDeCampos(corpo);
        var contrapartida = leitor.dinheiroNaoNegativo("contrapartida", CONTRAPARTIDA);
        if (contrapartida == null) {
            leitor.erro("contrapartida", CONTRAPARTIDA);
        }
        leitor.conferir(DadosDaFolhaInvalidosException::new);
        return HoleriteResponse.de(holerites.corrigirContrapartida(new HoleriteId(id), contrapartida));
    }

    @PutMapping("/adiantamentos/{id}")
    ResponseEntity<AdiantamentoResponse> adiantamento(@PathVariable UUID id,
                                                      @Valid @RequestBody AdiantamentoRequest corpo) {
        var resultado = adiantamentos.registrar(new RecebimentoId(id), Competencia.de(corpo.competencia()), new Dinheiro(corpo.valor()),
                corpo.data());
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(AdiantamentoResponse.de(resultado.adiantamento()));
    }

    @GetMapping("/rubricas")
    List<RubricaCatalogoResponse> rubricas() {
        return catalogo.listar().stream().map(RubricaCatalogoResponse::de).toList();
    }

    /** RF35, RN13: só a rubrica de gasto tem categoria, e sempre tem. */
    @PatchMapping("/rubricas")
    RubricaCatalogoResponse reclassificar(@RequestParam String codigo, @RequestBody Map<String, Object> corpo) {
        var leitor = new LeitorDeCampos(corpo);
        var natureza = leitor.enumeracaoObrigatoria("natureza", NaturezaRubrica.class);
        var categoria = leitor.id("categoria", CategoriaId::new);
        if (natureza == NaturezaRubrica.GASTO && categoria == null && !leitor.temErro("categoria")) {
            leitor.erro("categoria", "obrigatória na rubrica de gasto");
        } else if (natureza != null && natureza != NaturezaRubrica.GASTO && categoria != null) {
            leitor.erro("categoria", "só a rubrica de gasto tem categoria");
        }
        leitor.conferir(DadosDaFolhaInvalidosException::new);
        return RubricaCatalogoResponse.de(catalogo.reclassificar(codigo, natureza, categoria));
    }

    /** RF39: por mês e no ano, com o acumulado das contas FGTS e previdência. */
    @GetMapping("/resumo")
    ResumoFolhaService.Resumo resumo(@RequestParam int ano) {
        return resumos.resumo(ano);
    }

    @GetMapping("/contrapartida")
    List<RegraResponse> regras() {
        return contrapartidas.listar().stream().map(RegraResponse::de).toList();
    }

    @PostMapping("/contrapartida")
    @ResponseStatus(HttpStatus.CREATED)
    RegraResponse incluirRegra(@Valid @RequestBody RegraRequest corpo) {
        return RegraResponse.de(contrapartidas.incluir(new RegraContrapartida(
                Competencia.de(corpo.aPartirDe()), Taxa.dePontos(corpo.percentualDaBasica()))));
    }

    private static Dinheiro dinheiro(BigDecimal valor) {
        return valor == null ? null : new Dinheiro(valor);
    }
}
