# 05 — Módulo `reserva`

## Escopo

Aportes, categorias de reserva, planos de distribuição por percentual, projeção com juros compostos e metas com curva planejada contra real.

É o módulo mais simples em regra e o mais volátil em uso. Os percentuais foram reorganizados sete vezes numa única tarde de planejamento. **A arquitetura precisa tratar "mudar o plano" como operação rotineira e barata**, não como reconfiguração.

Nenhum valor é fixo no código. Percentuais, tetos, categorias e taxa de rendimento são todos dados.

---

## Modelo

```java
public record CategoriaReserva(
    CategoriaReservaId id,
    String nome,
    Dinheiro teto,          // null = sem teto
    int prioridade,         // desempate de arredondamento
    Status status
) {
    public enum Status { ATIVA, CONCLUIDA, ARQUIVADA }
}

public record Plano(
    PlanoId id,
    Competencia vigenteDesde,
    Map<CategoriaReservaId, Percentual> alocacoes,
    String motivo,          // por que este plano substituiu o anterior
    Instant criadoEm
) {
    public Plano {
        int soma = alocacoes.values().stream().mapToInt(Percentual::pontos).sum();
        if (soma != 100) throw new PlanoNaoSomaCemException(soma);
        alocacoes = Map.copyOf(alocacoes);
    }
}

public record Aporte(
    AporteId id,
    Competencia competencia,
    Dinheiro valor,
    ContaId contaDestino,
    Instant registradoEm
) {}

public record Distribuicao(
    DistribuicaoId id,
    AporteId aporte,
    PlanoId planoAplicado,
    Map<CategoriaReservaId, Dinheiro> fatias,
    Dinheiro sobra,
    CategoriaReservaId sobraDestinadaA,
    List<CategoriaReservaId> concluidasNesteAporte
) {}
```

O `init` do `Plano` é invariante de domínio, não validação de entrada: um plano que não soma 100% não deve conseguir existir na memória, nem por um instante.

O campo `motivo` parece supérfluo. Não é. Daqui a dois anos você vai olhar a linha do tempo de planos e a pergunta vai ser "por que eu troquei em dezembro?". Uma frase custa nada agora e vale muito depois.

`contaDestino` é o que fecha o laço da conciliação: sem saber em qual conta o aporte caiu, não dá para verificar se a soma das categorias bate com a soma das contas de reserva (ver `06-modulo-contas.md`).

---

## Planos são imutáveis e versionados

**Você nunca edita um plano.** Cria o próximo, com nova data de vigência. O anterior permanece intacto no banco — e o trigger de imutabilidade garante isso mesmo contra um `UPDATE` manual.

Isso dá de graça três coisas:

1. **Auditoria.** Toda distribuição registra qual plano aplicou. Um saldo estranho é sempre rastreável.
2. **Contrafactual.** "E se eu tivesse mantido o plano de dezembro?" é uma consulta, não uma reconstrução manual.
3. **Histórico de decisão.** A linha do tempo dos planos é o registro de como seu pensamento mudou.

O terceiro é o que justifica a decisão. Uma tabela com uma linha por versão de plano é barata; refazer a história depois é impossível.

```java
public interface PlanoRepository {
    Plano vigenteEm(Competencia c);
    List<Plano> historico();
    Plano criar(Plano novo);       // nunca update
}
```

`vigenteEm` retorna o plano com maior `vigenteDesde` menor ou igual à competência. Índice em `(vigente_desde DESC)`.

---

## O motor de distribuição

Três etapas, nesta ordem. A ordem importa.

### 1. Fatiar

Cada categoria recebe seu percentual do aporte, arredondado a duas casas.

### 2. Aplicar tetos e redistribuir excedente

Se a fatia da Emergência a levaria de R$ 11.800 para R$ 12.250, ela recebe apenas R$ 200 e os R$ 250 restantes são redistribuídos entre as categorias ainda ativas, **proporcionalmente aos percentuais delas**.

Essa redistribuição pode disparar em cascata: o excedente da Emergência pode estourar o teto da Moradia. O algoritmo precisa iterar até estabilizar, ou até não sobrar categoria ativa.

```java
private Redistribuicao aplicarTetos(Map<Id, Dinheiro> fatias, Saldos saldos, Plano plano) {
    var resultado = new HashMap<>(fatias);
    Dinheiro excedente = Dinheiro.ZERO;
    var ativas = new HashSet<>(plano.alocacoes().keySet());

    boolean estabilizou = false;
    while (!estabilizou) {
        estabilizou = true;
        for (var id : Set.copyOf(ativas)) {
            var teto = categoria(id).teto();
            if (teto == null) continue;
            var futuro = saldos.de(id).mais(resultado.get(id));
            if (futuro.valor().compareTo(teto.valor()) > 0) {
                excedente = excedente.mais(futuro.menos(teto));
                resultado.put(id, teto.menos(saldos.de(id)));
                ativas.remove(id);
                estabilizou = false;
            }
        }
        if (!excedente.equals(Dinheiro.ZERO) && !ativas.isEmpty()) {
            distribuirProporcional(excedente, ativas, plano, resultado);
            excedente = Dinheiro.ZERO;
            estabilizou = false;
        }
    }
    return new Redistribuicao(resultado, excedente);  // excedente > 0 se todas fecharam
}
```

**Caso terminal:** se todas as categorias atingirem o teto, sobra dinheiro sem destino. Não engula silenciosamente e não estoure exceção — retorne o excedente explicitamente e deixe a interface perguntar o que fazer. É um bom problema de se ter.

### 3. Fechar centavos

R$ 1.000 em 45/30/15/10 fecha exato. R$ 1.234,56 não. A sobra vai integralmente para a categoria de **maior prioridade** entre as ativas, e fica registrada em `sobraDestinadaA`.

### A invariante do módulo

> A soma das fatias mais o excedente não alocado é **sempre exatamente igual** ao valor do aporte. Para qualquer valor, qualquer plano, qualquer combinação de saldos e tetos.

Este é o teste de propriedade que vale mais que qualquer conjunto de exemplos. Rode com valores e planos aleatórios, milhares de vezes.

---

## Transição de plano

Quando a Emergência fecha o teto, os percentuais dela precisam ir para algum lugar. Duas abordagens:

| | Automática | Manual com sugestão |
|---|---|---|
| Como | Regra declarada: `AoConcluir(X) → redistribuirProporcional` | Sistema detecta, propõe plano novo, espera confirmação |
| Prós | Sem intervenção | Você decide no momento |
| Contras | Honra decisão tomada anos antes | Exige um clique |

**Use a manual.** A Emergência deve fechar em meados de 2028. Uma regra escrita hoje sobre o que fazer com 30% do aporte daqui a dois anos vai estar errada — seu histórico mostra que você reavalia prioridade sempre que vê o número atualizado.

A resposta da distribuição já carrega `concluidasNesteAporte`. A interface usa isso para propor o plano seguinte na hora.

---

## Projeção

Aporte crescente quebra a fórmula fechada de anuidade. Iteração mês a mês:

```java
public List<PontoProjecao> projetar(
        Dinheiro saldoInicial,
        SortedMap<Competencia, Dinheiro> aportesPrevistos,
        TaxaMensal rendimento,
        Competencia ate) {

    var pontos = new ArrayList<PontoProjecao>();
    Dinheiro saldo = saldoInicial;

    for (var c : aportesPrevistos.keySet()) {
        if (c.compareTo(ate) > 0) break;
        saldo = new Dinheiro(saldo.valor().multiply(BigDecimal.ONE.add(rendimento.fracao())))
                    .mais(aportesPrevistos.get(c));
        pontos.add(new PontoProjecao(c, saldo));
    }
    return pontos;
}
```

**Convenção: o aporte entra no fim do mês e não rende naquele mês.** É conservador e bate com quem aporta no dia do salário.

Documente isso na resposta da API. Sem essa nota, daqui a seis meses você compara com o extrato, encontra diferença de alguns reais, e passa uma tarde caçando bug que não existe.

**A taxa de rendimento é dado, não constante.** Guarde qual taxa foi usada em cada projeção. Quando a Selic mudar, você vai querer saber com que premissa a curva antiga foi traçada.

---

## Metas: planejado contra real

```java
public record Meta(
    MetaId id,
    CategoriaReservaId categoria,
    Dinheiro alvo,
    Competencia criadaEm,
    TaxaMensal taxaPremissa,
    List<PontoProjecao> curvaPlanejada,   // CONGELADA
    Competencia previsaoOriginal
) {}
```

**A curva planejada é congelada no momento da criação.** Este é o ponto mais fácil de errar do módulo.

Se você recalcular a projeção toda vez que abrir o app, a linha "planejado" persegue a linha "real" e as duas nunca se separam — o gráfico fica bonito e não informa nada. O desvio entre planejado e real **é** a informação. Congelar a curva é o que torna o desvio visível.

Quando você mudar de plano e quiser uma nova referência, crie uma **nova meta**. A antiga fica no histórico, junto com a curva que ela previa e a premissa de rendimento que usou.

### Simulação de cenário

```
POST /api/reserva/projecao/simular
{
  "aporteBase": 1500.00,
  "escadaMensal": 100.00,
  "tetoAporte": 1500.00,
  "rendimento": 0.008,
  "ate": "2028-12",
  "plano": { ... }          // opcional: plano hipotético
}
```

**Não persiste nada.** Retorna a curva como terceira série, para o gráfico sobrepor sem tocar no plano salvo.

Este endpoint não é acessório. Você simulou sete cenários antes de fixar o plano atual — simular sem se comprometer é como você decide. Trate-o como funcionalidade de primeira classe, com a mesma qualidade dos endpoints de escrita.

---

## Fachada pública e eventos

```java
public interface ReservaFacade {

    /** Usado pelo sistema: missão "aporte do mês" pendente ou feita. */
    boolean aporteRegistrado(Competencia c);

    /** Usado pelo sistema (revisão da semana) e por contas (conciliação cruzada). */
    List<ProgressoMeta> progressoDasMetas();   // categoria, saldo, teto, previsão

    /** Usado por contas para a conciliação entre contas de reserva e categorias. */
    Dinheiro totalDasCategorias();
}
```

Evento publicado:

| Evento | Quando | Dados |
|---|---|---|
| `AporteFeito` | Aporte persistido e distribuído | `aporteId`, `competencia`, `valor`, `contaDestino`, `concluidasNesteAporte` |

O `sistema` usa o evento para cumprir a missão do mês. A missão sugere o valor a partir da sobra do mês (`fluxo.resumoDoMes`), mas **não aporta nada sozinha** — regra 6 do `00-arquitetura.md`.

---

## Endpoints

```
POST   /api/reserva/aportes
GET    /api/reserva/aportes/previa?valor=1200.00   ← a cada tecla, sem efeito colateral
GET    /api/reserva/aportes?de=2026-09&ate=2027-08
DELETE /api/reserva/aportes/{id}                   ← estorna a distribuição

GET    /api/reserva/saldos
GET    /api/reserva/categorias
POST   /api/reserva/categorias
PATCH  /api/reserva/categorias/{id}                ← teto e prioridade

GET    /api/reserva/planos/vigente
GET    /api/reserva/planos                         ← histórico completo
POST   /api/reserva/planos                         ← cria versão nova, nunca edita

GET    /api/reserva/projecao?ate=2028-12
POST   /api/reserva/projecao/simular
GET    /api/reserva/projecao/contrafactual?planoId={id}   ← "e se eu tivesse mantido"

GET    /api/reserva/metas
POST   /api/reserva/metas
```

`DELETE` de aporte precisa estornar a distribuição inteira, incluindo reverter categorias que tenham sido marcadas como concluídas por causa dele. Transação única.

---

## Schema

```sql
CREATE SCHEMA reserva;

CREATE TABLE reserva.categorias (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    nome        TEXT NOT NULL UNIQUE,
    teto        NUMERIC(19,2) CHECK (teto IS NULL OR teto > 0),
    prioridade  INT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('ATIVA','CONCLUIDA','ARQUIVADA'))
);

CREATE TABLE reserva.planos (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    vigente_desde  DATE NOT NULL CHECK (EXTRACT(DAY FROM vigente_desde) = 1),
    motivo         TEXT,
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reserva.plano_alocacoes (
    plano_id      UUID NOT NULL REFERENCES reserva.planos(id),
    categoria_id  UUID NOT NULL REFERENCES reserva.categorias(id),
    pontos        INT NOT NULL CHECK (pontos BETWEEN 0 AND 100),
    PRIMARY KEY (plano_id, categoria_id)
);

CREATE TABLE reserva.aportes (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    competencia    DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    valor          NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    conta_destino  UUID NOT NULL REFERENCES contas.contas(id),
    registrado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reserva.distribuicoes (
    id                   UUID PRIMARY KEY DEFAULT uuidv7(),
    aporte_id            UUID NOT NULL UNIQUE REFERENCES reserva.aportes(id) ON DELETE CASCADE,
    plano_id             UUID NOT NULL REFERENCES reserva.planos(id),
    sobra                NUMERIC(19,2) NOT NULL DEFAULT 0,
    sobra_destinada_a    UUID REFERENCES reserva.categorias(id),
    excedente_nao_alocado NUMERIC(19,2) NOT NULL DEFAULT 0
);

CREATE TABLE reserva.distribuicao_fatias (
    distribuicao_id  UUID NOT NULL REFERENCES reserva.distribuicoes(id) ON DELETE CASCADE,
    categoria_id     UUID NOT NULL REFERENCES reserva.categorias(id),
    valor            NUMERIC(19,2) NOT NULL,
    PRIMARY KEY (distribuicao_id, categoria_id)
);

CREATE TABLE reserva.metas (
    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
    categoria_id       UUID NOT NULL REFERENCES reserva.categorias(id),
    alvo               NUMERIC(19,2) NOT NULL,
    criada_em          DATE NOT NULL,
    taxa_premissa      NUMERIC(10,8) NOT NULL,
    curva_planejada    JSONB NOT NULL,        -- congelada
    previsao_original  DATE
);

-- Planos são versionados, nunca editados (ver 00-arquitetura)
CREATE TRIGGER planos_imutaveis
    BEFORE UPDATE OR DELETE ON reserva.planos
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();

CREATE TRIGGER plano_alocacoes_imutaveis
    BEFORE UPDATE OR DELETE ON reserva.plano_alocacoes
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();

CREATE INDEX ON reserva.planos (vigente_desde DESC);
CREATE INDEX ON reserva.aportes (competencia DESC);
```

**Saldo não tem tabela.** É a soma das fatias por categoria — uma view materializada ou uma consulta agregada. Manter coluna de saldo cria a possibilidade de divergência entre o saldo gravado e a soma das distribuições, e essa divergência é impossível de diagnosticar depois.

```sql
CREATE VIEW reserva.saldos AS
SELECT categoria_id, SUM(valor) AS saldo
FROM reserva.distribuicao_fatias
GROUP BY categoria_id;
```

Se performance virar problema — não vai, com um usuário — materialize a view. Não adicione coluna.

---

## Testes

```java
// Invariantes
@Test void soma_das_fatias_mais_excedente_igual_ao_aporte()   // propriedade
@Test void plano_que_nao_soma_cem_nao_pode_ser_criado()

// Arredondamento
@Test void sobra_de_centavo_vai_para_maior_prioridade()
@Test void sobra_fica_registrada_e_e_auditavel()

// Tetos
@Test void categoria_no_teto_recebe_apenas_a_diferenca()
@Test void excedente_redistribui_proporcional_entre_ativas()
@Test void cascata_de_tetos_estabiliza()
@Test void todas_no_teto_retorna_excedente_nao_alocado()

// Planos
@Test void plano_nunca_e_atualizado_apenas_versionado()
@Test void update_em_plano_e_rejeitado_pelo_banco()
@Test void distribuicao_registra_qual_plano_aplicou()
@Test void vigente_em_retorna_o_plano_correto_da_competencia()
@Test void contrafactual_calcula_com_plano_antigo()

// Aportes
@Test void aporte_exige_conta_de_destino()
@Test void aporte_publica_evento_apos_commit()

// Projeção e metas
@Test void aporte_nao_rende_no_mes_em_que_entra()
@Test void curva_planejada_nao_muda_apos_criacao_da_meta()
@Test void simulacao_nao_persiste_nada()

// Estorno
@Test void deletar_aporte_reverte_fatias_e_status_de_categoria()
```

O `curva_planejada_nao_muda_apos_criacao_da_meta` protege contra a regressão mais provável do módulo. É o tipo de coisa que alguém "otimiza" recalculando, e o gráfico para de informar sem que ninguém perceba.
