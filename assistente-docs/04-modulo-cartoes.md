# 04 — Módulo `cartoes`

## Escopo

Cartões de crédito, parcelas, faturas e o calendário que decide quando cada coisa cai. É o módulo com a regra mais densa do sistema.

Toda a complexidade aqui vem de uma coisa: **o cartão de crédito tem calendário próprio, que não é o calendário civil.** Uma compra feita dia 28 pode pertencer ao mês seguinte. Um vencimento dia 14 pode ser pago dia 16. Errar isso desalinha a previsão inteira.

---

## Modelo

```java
public record Cartao(
    CartaoId id,
    String apelido,
    Dinheiro limite,
    int diaFechamento,
    int diaVencimento,
    ContaId contaPagamento,
    RegraFeriado regraFeriado,
    boolean ativo
) {
    public Cartao {
        if (diaFechamento < 1 || diaFechamento > 31) throw ...;
        if (diaVencimento < 1 || diaVencimento > 31) throw ...;
    }
}

public record Parcela(
    ParcelaId id,
    GastoId gasto,
    CartaoId cartao,
    int numero,              // 3
    int total,               // de 12
    Dinheiro valor,
    Competencia faturaCompetencia
) {}
```

`contaPagamento` é de onde a fatura sai. É o que permite a projeção de saldo de `contas` saber qual conta a fatura vai derrubar.

**Parcela é entidade persistida, não valor calculado.** A tentação é derivar as parcelas do gasto em tempo de leitura — valor dividido por N. Não faça. Duas razões: a divisão inexata precisa ser registrada (ver adiante), e uma compra pode ser estornada ou renegociada parcialmente, o que quebra qualquer fórmula.

---

## Regra 1: o fechamento decide a fatura

Esta é a regra que mais confunde na prática e a que mais desalinha previsão.

Compra feita **até** o dia de fechamento entra na fatura que fecha naquele mês. Compra feita **depois** do fechamento entra na fatura seguinte.

Cartão com fechamento dia 25 e vencimento dia 5:

| Compra em | Fecha em | Vence em |
|---|---|---|
| 20/set | 25/set | 05/out |
| 25/set | 25/set | 05/out |
| 26/set | 25/out | 05/nov |
| 30/set | 25/out | 05/nov |

Seis dias de diferença na compra viram um mês inteiro na cobrança.

```java
public Competencia faturaDeEstreia(Cartao cartao, LocalDate compra) {
    int fechamento = diaEfetivo(cartao.diaFechamento(), compra.getYear(), compra.getMonth());
    YearMonth mes = YearMonth.from(compra);
    return new Competencia(compra.getDayOfMonth() <= fechamento ? mes : mes.plusMonths(1));
}
```

`diaEfetivo` trata fechamento dia 31 em fevereiro: usa o último dia do mês.

**A interface precisa comunicar isso sem exigir raciocínio do usuário.** Ao registrar uma compra, o backend devolve em qual fatura ela cai e quando essa fatura vence. Perto do fechamento, essa é a informação mais valiosa da tela — muda a decisão de comprar hoje ou esperar dois dias.

### Parcelas seguintes

A parcela 1 cai na fatura de estreia; as demais em competências consecutivas.

```java
List<Parcela> gerar(GastoId gasto, Cartao cartao, Dinheiro valor, int n, LocalDate data) {
    Competencia estreia = faturaDeEstreia(cartao, data);
    List<Dinheiro> valores = dividir(valor, n);
    return IntStream.range(0, n)
        .mapToObj(i -> new Parcela(null, gasto, cartao.id(),
                                   i + 1, n, valores.get(i), estreia.mais(i)))
        .toList();
}
```

---

## Regra 2: divisão inexata de parcelas

R$ 100 em 3x não é R$ 33,33 três vezes — isso soma R$ 99,99.

Convenção do sistema: **a diferença vai na primeira parcela.** R$ 33,34 + R$ 33,33 + R$ 33,33.

```java
static List<Dinheiro> dividir(Dinheiro total, int n) {
    BigDecimal base = total.valor()
        .divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
    Dinheiro resto = new Dinheiro(
        total.valor().subtract(base.multiply(BigDecimal.valueOf(n))));

    List<Dinheiro> out = new ArrayList<>(n);
    out.add(new Dinheiro(base).mais(resto));
    for (int i = 1; i < n; i++) out.add(new Dinheiro(base));
    return out;
}
```

Primeira parcela e não última porque é assim que emissor brasileiro costuma fazer, e a previsão fica mais próxima da fatura real.

**Invariante obrigatória:** a soma das parcelas é sempre exatamente igual ao valor da compra. Para qualquer valor e qualquer N. Este é o teste de propriedade do módulo.

---

## Regra 3: vencimento e dia sem compensação

O critério não é "feriado nacional" — é **dia sem expediente bancário**. Carnaval não é feriado nacional oficial, mas os bancos não compensam, e a Febraban confirma que fatura vencida nesses dias pode ser paga no dia útil seguinte sem encargo. A base legal é a Lei 7.089/83, e a jurisprudência aplica o mesmo a feriado municipal e estadual sem expediente.

Ou seja: o conjunto relevante é maior que os feriados nacionais.

### Mas o app mira a data nominal

Aqui há uma assimetria de custo que decide o desenho:

| App aponta | Banco pratica | Consequência |
|---|---|---|
| Data adiada | Adia também | Correto |
| Data adiada | Não adia | **Atraso → juros de rotativo** |
| Data nominal | Adia | Pagou adiantado — sem custo |

O erro para o lado tardio custa a linha de crédito mais cara do país. O erro para o lado cedo custa nada. E como a prática varia entre emissores — o contrato de alguns define o deslocamento só sobre feriado nacional — a única postura defensável é a conservadora.

**Portanto:**

- `vencimentoNominal` é a **data-alvo** que a interface exibe como prazo de pagamento
- `limiteSemEncargo` é a data adiada, exibida como informação secundária
- A interface nunca apresenta a data adiada como "o vencimento"

Isso vale também para a agenda (documento 11): o evento de vencimento vai na data nominal.

```java
public record DatasVencimento(
    LocalDate nominal,            // alvo: é isto que a UI mostra como prazo
    LocalDate limiteSemEncargo,   // adiada; informativa
    boolean houveDeslocamento,
    List<String> motivos          // ["Sábado", "Carnaval (feriado bancário)"]
) {}

public DatasVencimento vencimento(Cartao cartao, Competencia fatura) {
    LocalDate nominal = diaEfetivoComoData(cartao.diaVencimento(), fatura);
    LocalDate limite = nominal;
    var motivos = new ArrayList<String>();
    while (!calendario.ehDiaUtil(limite, cartao.regraFeriado())) {
        motivos.add(calendario.motivo(limite));
        limite = limite.plusDays(1);
    }
    return new DatasVencimento(nominal, limite, !nominal.equals(limite), motivos);
}
```

**O adiamento é para adiar, nunca para antecipar.** Se cair em dia sem compensação, empurra para frente.

### Nota sobre PIX

Fatura paga por PIX compensa na hora, sete dias por semana, inclusive em feriado. Para quem paga pelo app — provavelmente você — a discussão de dia útil quase não tem efeito prático: pagar no domingo dia 14 funciona.

O deslocamento importa mesmo para boleto e débito automático, que dependem de compensação. Vale a interface deixar isso claro, porque é o argumento mais forte a favor de simplesmente pagar na data nominal e não pensar no assunto.

### O calendário de feriados

Feriado nacional brasileiro tem duas naturezas:

**Fixos:** 1/jan, 21/abr, 1/mai, 7/set, 12/out, 2/nov, 15/nov, 20/nov, 25/dez.

**Móveis**, todos derivados da Páscoa:

| Feriado | Deslocamento |
|---|---|
| Carnaval (terça) | Páscoa − 47 dias |
| Sexta-feira Santa | Páscoa − 2 dias |
| Corpus Christi | Páscoa + 60 dias |

A Páscoa se calcula pelo algoritmo gregoriano anônimo (Meeus/Jones/Butcher):

```java
static LocalDate pascoa(int ano) {
    int a = ano % 19, b = ano / 100, c = ano % 100;
    int d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
    int h = (19 * a + b - d - g + 15) % 30;
    int i = c / 4, k = c % 4;
    int l = (32 + 2 * e + 2 * i - h - k) % 7;
    int m = (a + 11 * h + 22 * l) / 451;
    int mes = (h + l - 7 * m + 114) / 31;
    int dia = ((h + l - 7 * m + 114) % 31) + 1;
    return LocalDate.of(ano, mes, dia);
}
```

Vale a pena escrever à mão. São doze linhas, é determinístico, e evita depender de biblioteca que pode não cobrir o Brasil.

### Escopos de feriado

Como a prática varia entre emissores e você não tem como saber a regra de cada contrato sem lê-lo, o calendário é **classificado por escopo** e cada cartão declara qual conjunto aplica.

```sql
CREATE TABLE cartoes.feriados (
    data      DATE PRIMARY KEY,
    nome      TEXT NOT NULL,
    escopo    TEXT NOT NULL CHECK (escopo IN
              ('NACIONAL','BANCARIO','ESTADUAL','MUNICIPAL')),
    calculado BOOLEAN NOT NULL DEFAULT false
);
```

| Escopo | Exemplos | Natureza |
|---|---|---|
| `NACIONAL` | 1/jan, Sexta-feira Santa, 21/abr, 1/mai, 7/set, 12/out, 2/nov, 15/nov, 20/nov, 25/dez | Feriado por lei federal |
| `BANCARIO` | Carnaval (seg e ter), Corpus Christi | Ponto facultativo, mas sem compensação |
| `ESTADUAL` | 9/jul em SP | Lei estadual |
| `MUNICIPAL` | Aniversário de Sumaré, padroeiro | Lei municipal |

E no cartão:

```java
public enum RegraFeriado {
    SO_NACIONAL,        // conservador: só feriado federal desloca
    BANCARIO,           // + Carnaval e Corpus Christi
    COMPLETO            // + estaduais e municipais
}
```

Default `SO_NACIONAL`, pelo argumento da assimetria acima. Você troca por cartão conforme descobrir o comportamento real de cada emissor — e vai descobrir observando: se a fatura de fevereiro venceu na terça de Carnaval e não veio encargo, aquele emissor pratica `BANCARIO`.

**Feriado municipal exige tabela.** Bancos fecham no aniversário do município, e biblioteca genérica de feriados brasileiros não cobre Sumaré. Por isso a carga manual continua necessária, mesmo que o default não a use.

Estratégia de manutenção: um job anual materializa nacionais e bancários dos próximos anos (`calculado = true`); estaduais e municipais entram à mão (`calculado = false`). A regeneração apaga só os calculados, preservando o que você cadastrou.

---

## Regra 4: composição da fatura

Fatura é **projeção**, não entidade persistida. Ela se calcula somando o que cai naquela competência.

```java
public record Fatura(
    CartaoId cartao,
    Competencia competencia,
    LocalDate fechamento,
    DatasVencimento vencimento,
    List<LinhaFatura> linhas,
    Dinheiro total,
    StatusFatura status
) {}

public sealed interface LinhaFatura {
    record CompraAVista(GastoId g, String desc, Dinheiro v, LocalDate data)
        implements LinhaFatura {}
    record ParcelaDeCompra(GastoId g, String desc, Dinheiro v, int numero, int total)
        implements LinhaFatura {}
    record Assinatura(RecorrenteId r, String desc, Dinheiro v)
        implements LinhaFatura {}
}

public enum StatusFatura { ABERTA, FECHADA, PAGA }
```

Cada linha é rastreável ao item original — a interface precisa permitir clicar numa parcela "3 de 12" e chegar na compra que a originou.

**Exceção à regra de não persistir:** quando a fatura é paga, materialize. Fatura paga é fato histórico e não deve se recalcular quando você editar um gasto antigo ou trocar a regra de arredondamento. O pagamento também gera um `PagamentoFatura` em `contas`, debitando a conta de pagamento.

---

## Limite

```java
public record SituacaoLimite(
    Dinheiro limiteTotal,
    Dinheiro comprometido,      // tudo que ainda não foi pago
    Dinheiro disponivel,
    BigDecimal percentualUso
) {}
```

**Comprometido inclui parcelas futuras.** Uma compra de R$ 12.000 em 12x consome R$ 12.000 do limite no ato — não R$ 1.000. O limite só se libera conforme as faturas são pagas.

Esse é o erro de intuição mais caro do cartão de crédito: achar que parcelar preserva limite. Deixe explícito na resposta.

Acima de 80% de uso, sinalize. Fato operacional, sem sermão.

---

## Fachada pública e eventos

Único ponto que outros módulos enxergam:

```java
public interface CartoesFacade {

    /** Chamado por fluxo ao registrar gasto no crédito. */
    ResultadoParcelamento gerarParcelas(GastoId g, CartaoId c, Dinheiro v,
                                        int parcelas, LocalDate data);

    /** Chamado por fluxo para montar o horizonte de 12 meses. */
    Map<Competencia, Dinheiro> totalPorCompetencia(Competencia de, Competencia ate);

    /** Prévia sem persistir. */
    PreviaParcelamento simular(CartaoId c, Dinheiro v, int parcelas, LocalDate data);

    /** Chamado por contas (projeção de saldo), agenda (vencimentos) e sistema (missões). */
    List<VencimentoPrevisto> proximosVencimentos(LocalDate ate);
    //   VencimentoPrevisto(cartao, apelido, competencia, valor,
    //                      DatasVencimento datas, ContaId contaPagamento, boolean paga)
}
```

`fluxo`, `contas`, `agenda` e `sistema` não conhecem `Parcela`, `Fatura` nem `Cartao` internamente. Recebem DTO.

Evento publicado:

| Evento | Quando | Dados |
|---|---|---|
| `FaturaPaga` | `POST .../pagar` concluído | `cartaoId`, `competencia`, `valor`, `contaId`, `pagoEm` |

Não há evento de "fatura fechou": fechamento é uma data, não uma ação. Quem precisa saber consulta `proximosVencimentos`.

---

## Endpoints

```
GET    /api/cartoes
POST   /api/cartoes
PATCH  /api/cartoes/{id}
GET    /api/cartoes/{id}/fatura?competencia=2026-09
GET    /api/cartoes/{id}/faturas?de=2026-09&ate=2027-08
GET    /api/cartoes/{id}/limite
POST   /api/cartoes/{id}/faturas/{competencia}/pagar

GET    /api/cartoes/{id}/proximo-fechamento
       → { "data": "2026-09-25", "diasRestantes": 4 }

GET    /api/cartoes/vencimentos?ate=2026-12-31

GET    /api/feriados?ano=2027
POST   /api/feriados          ← estaduais e municipais, manual
```

`proximo-fechamento` parece trivial mas é consultado toda vez que você pensa em comprar algo. Cartão prestes a fechar muda a decisão.

---

## Schema

```sql
CREATE SCHEMA cartoes;

CREATE TABLE cartoes.cartoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    apelido         TEXT NOT NULL,
    limite          NUMERIC(19,2) NOT NULL CHECK (limite > 0),
    dia_fechamento  INT NOT NULL CHECK (dia_fechamento BETWEEN 1 AND 31),
    dia_vencimento  INT NOT NULL CHECK (dia_vencimento BETWEEN 1 AND 31),
    conta_pagamento UUID NOT NULL REFERENCES contas.contas(id),
    regra_feriado   TEXT NOT NULL DEFAULT 'SO_NACIONAL'
                    CHECK (regra_feriado IN ('SO_NACIONAL','BANCARIO','COMPLETO')),
    ativo           BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE cartoes.parcelas (
    id                   UUID PRIMARY KEY DEFAULT uuidv7(),
    gasto_id             UUID NOT NULL REFERENCES fluxo.gastos(id) ON DELETE CASCADE,
    cartao_id            UUID NOT NULL REFERENCES cartoes.cartoes(id),
    numero               INT NOT NULL CHECK (numero > 0),
    total                INT NOT NULL CHECK (total > 0),
    valor                NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    fatura_competencia   DATE NOT NULL CHECK (EXTRACT(DAY FROM fatura_competencia) = 1),
    CONSTRAINT numero_valido CHECK (numero <= total),
    UNIQUE (gasto_id, numero)
);

CREATE TABLE cartoes.faturas_pagas (
    cartao_id     UUID NOT NULL REFERENCES cartoes.cartoes(id),
    competencia   DATE NOT NULL,
    total         NUMERIC(19,2) NOT NULL,
    pago_em       DATE NOT NULL,
    conta_id      UUID NOT NULL REFERENCES contas.contas(id),
    snapshot      JSONB NOT NULL,
    PRIMARY KEY (cartao_id, competencia)
);

-- Fatura paga é fato consumado (ver 00-arquitetura, triggers de imutabilidade)
CREATE TRIGGER faturas_pagas_imutaveis
    BEFORE UPDATE OR DELETE ON cartoes.faturas_pagas
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();

CREATE INDEX ON cartoes.parcelas (cartao_id, fatura_competencia);
CREATE INDEX ON cartoes.parcelas (fatura_competencia);
```

`ON DELETE CASCADE` de gasto para parcelas: apagar um gasto no crédito remove suas parcelas. É a única cascata do sistema, e ela é correta — parcela órfã não significa nada.

`snapshot JSONB` na fatura paga guarda a composição no momento do pagamento. É o que permite auditar meses depois sem depender de recalcular com regras que podem ter mudado.

`PRIMARY KEY (cartao_id, competencia)` em `faturas_pagas` também torna o pagamento idempotente: pagar a mesma fatura duas vezes é rejeitado pelo banco.

---

## Testes

O módulo com mais caso de borda do sistema.

```java
// Fechamento
@Test void compra_no_dia_do_fechamento_entra_na_fatura_atual()
@Test void compra_um_dia_apos_fechamento_pula_para_a_seguinte()
@Test void fechamento_dia_31_em_fevereiro_usa_ultimo_dia()

// Parcelamento
@Test void cem_reais_em_tres_vezes_soma_exatamente_cem()
@Test void diferenca_de_arredondamento_vai_na_primeira_parcela()
@Test void parcela_doze_de_doze_cai_onze_meses_apos_a_estreia()

// Dia útil
@Test void vencimento_no_sabado_mantem_nominal_e_marca_limite_na_segunda()
@Test void vencimento_no_domingo_marca_limite_na_segunda()
@Test void vencimento_na_sexta_santa_desloca_em_qualquer_regra()
@Test void carnaval_nao_desloca_na_regra_so_nacional()
@Test void carnaval_desloca_na_regra_bancario()
@Test void feriado_municipal_desloca_apenas_na_regra_completo()
@Test void feriado_emendado_com_fim_de_semana_pula_tudo()
@Test void deslocamento_nunca_antecipa()
@Test void motivos_do_deslocamento_sao_listados()

// Páscoa — âncoras conhecidas
@Test void pascoa_2027_e_28_de_marco()
@Test void pascoa_2028_e_16_de_abril()

// Limite
@Test void compra_parcelada_consome_limite_integral_no_ato()
@Test void limite_se_libera_conforme_faturas_sao_pagas()

// Fatura
@Test void fatura_soma_a_vista_parcelas_e_assinaturas()
@Test void fatura_paga_nao_muda_ao_editar_gasto_antigo()
@Test void pagar_fatura_debita_conta_de_pagamento()
@Test void pagar_fatura_publica_evento()
@Test void pagar_a_mesma_fatura_duas_vezes_e_rejeitado()

// Fachada
@Test void proximos_vencimentos_trazem_data_nominal_e_limite()
```

E a propriedade central:

> Para qualquer valor e qualquer N entre 1 e 24, a soma das parcelas geradas é exatamente igual ao valor da compra.

Vale mais que os exemplos individuais. Rode com valores aleatórios.
