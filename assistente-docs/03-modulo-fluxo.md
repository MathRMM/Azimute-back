# 03 — Módulo `fluxo`

## Escopo

Ganhos, gastos, categorias e lançamentos recorrentes. Mais a consolidação de doze meses que junta tudo isso com as parcelas vindas de `cartoes`.

É o módulo com mais volume de escrita do sistema — registrar gasto é a ação mais frequente. Precisa ser barato, e precisa funcionar offline.

---

## Ganhos

### Bruto e líquido

Todo ganho registra os dois valores. O bruto é referência; o líquido é o que entra na conta e o que alimenta todo cálculo do sistema.

```java
public record Ganho(
    GanhoId id,
    Competencia competencia,
    String descricao,
    Dinheiro bruto,
    Dinheiro liquido,
    TipoGanho tipo,
    ContaId contaDestino,
    LocalDate recebidoEm
) {
    public Ganho {
        if (liquido.valor().compareTo(bruto.valor()) > 0)
            throw new LiquidoMaiorQueBrutoException(bruto, liquido);
    }

    public BigDecimal taxaDesconto() {
        if (bruto.valor().signum() == 0) return BigDecimal.ZERO;
        return BigDecimal.ONE.subtract(
            liquido.valor().divide(bruto.valor(), MathContext.DECIMAL64));
    }
}

public enum TipoGanho { SALARIO, PLR, FREELA, REEMBOLSO, OUTRO }
```

A validação no construtor é invariante de domínio, não de entrada: líquido maior que bruto é um estado que não deve conseguir existir no sistema.

`contaDestino` é onde o líquido cai. É o que gera a `Entrada` em `contas`.

### A métrica de desconto

O motivo do campo duplo existir. Conhecendo o histórico de desconto, dá para prever o líquido de um ganho futuro **antes de recebê-lo**.

```java
public interface ProjecaoGanho {
    /** Média de desconto dos últimos N ganhos de um tipo. */
    BigDecimal descontoMedio(TipoGanho tipo, int ultimosN);

    /** Líquido estimado para um bruto futuro. */
    Dinheiro estimarLiquido(Dinheiro bruto, TipoGanho tipo);
}
```

**Segregue por tipo.** Salário e PLR têm tributação diferente — PLR tem tabela própria, mais branda que a do salário. Misturar os dois numa média só produz previsão ruim para ambos. Se você aplicar o desconto médio de salário à PLR de março, o erro é grande o bastante para atrapalhar o planejamento.

Use os últimos 6 lançamentos por padrão, não a média histórica inteira. Faixa de INSS e IRRF muda, e média longa carrega premissa velha.

---

## Gastos

### A bifurcação central

```java
public sealed interface FormaPagamento {
    record AVista(ContaId conta) implements FormaPagamento {}
    record Credito(CartaoId cartao, int parcelas) implements FormaPagamento {}
}
```

**A conta é o atributo, não a modalidade.** Pix e débito saem da mesma conta corrente; dinheiro sai da carteira. Registrar `DEBITO` ou `PIX` não diz de onde o dinheiro saiu — registrar a conta diz, e é o que permite conciliar com extrato.

`sealed` porque as duas formas produzem consequências radicalmente diferentes e o compilador deve exigir que todo tratamento cubra as duas:

- **À vista** debita a conta informada e afeta apenas a competência corrente. Termina no registro.
- **Crédito** não debita conta nenhuma no ato. Gera N parcelas em `cartoes`, que caem em faturas de meses futuros determinadas pelo dia de fechamento — e só então debitam a conta de pagamento do cartão.

```java
public record Gasto(
    GastoId id,                     // gerado no cliente (registro offline)
    LocalDate data,
    String descricao,
    Dinheiro valor,
    CategoriaId categoria,
    FormaPagamento forma,
    RecorrenteId origemRecorrente   // null se lançamento avulso
) {}
```

`data` aqui é `LocalDate`, não `Competencia` — para gasto no crédito, o **dia** importa: é ele que decide em qual fatura cai.

### Fluxo de registro no crédito

Registrar um gasto no crédito é uma operação que cruza módulos:

```
fluxo.registrarGasto(comando)
  ├─ persiste o Gasto
  ├─ se forma == AVista:
  │    contas.registrarSaida(conta, gastoId, valor, data)
  ├─ se forma == Credito:
  │    cartoes.gerarParcelas(gastoId, cartaoId, valor, parcelas, data)
  │      └─ retorna as competências afetadas e a fatura de estreia
  └─ publica GastoLancado
```

Transação única. Se a geração de parcelas falhar, o gasto não é persistido — não existe gasto no crédito sem parcela. O evento `GastoLancado` só é entregue depois do commit (Spring Modulith).

O comando de prévia faz o mesmo caminho **sem persistir**, e é o que alimenta a interface enquanto o usuário digita:

```
GET /api/gastos/previa?cartao={id}&valor=1200.00&parcelas=6&data=2026-09-28

→ { "parcelaUnitaria": 200.00,
    "faturaEstreia": "2026-10",
    "ultimaFatura": "2027-03",
    "impactoPorMes": [ {"competencia":"2026-10","valor":200.00}, ... ],
    "avisoFechamento": "Cartão fecha dia 25. Esta compra cai na fatura de outubro." }
```

O `avisoFechamento` é a informação mais útil da tela quando a data está perto do fechamento.

### Registro offline

Gasto é a ação mais frequente do app, e muitas vezes acontece sem sinal (no mercado, no estacionamento). Por isso o gasto usa ID gerado no cliente e `PUT /api/gastos/{id}` idempotente, passando pela fila `sync` do app. Offline, a prévia não está disponível; o app registra mesmo assim e mostra em qual fatura caiu quando sincronizar.

O mesmo vale para ganhos: `PUT /api/ganhos/{id}`.

---

## Categorias

```java
public record Categoria(
    CategoriaId id,
    String nome,
    boolean predefinida,
    boolean ativa
) {}
```

Pré-definidas na migration inicial: Alimentação, Moradia, Transporte, Saúde, Educação, Lazer, Assinaturas, Vestuário, Outros.

Duas regras:

**Criação inline.** Categoria nova é criada no meio do fluxo de registro de gasto, não numa tela de configuração. `POST /api/categorias` retorna o ID, o front já usa. Se criar categoria custa sair do fluxo, você vai jogar tudo em "Outros" e a categorização perde o sentido.

**Categoria não se apaga, se desativa.** Gastos históricos apontam para ela. `ativa = false` some das listas de seleção mas preserva o histórico. Deletar em cascata destruiria meses de registro.

---

## Recorrentes

O problema real: assinatura é valor pequeno, cobrança automática, ninguém lembra. Somadas, viram dinheiro relevante que não aparece em lugar nenhum.

```java
public record Recorrente(
    RecorrenteId id,
    String descricao,
    Dinheiro valor,
    CategoriaId categoria,
    Periodicidade periodicidade,
    int diaCobranca,
    Month mesCobranca,          // só para ANUAL
    FormaPagamento forma,
    Competencia inicio,
    Competencia fim,            // null = indefinido
    boolean ativa
) {}

public enum Periodicidade { MENSAL, ANUAL }
```

### A regra da anuidade

Uma assinatura anual de R$ 240 cobrada em março **não é R$ 20 por mês.** É R$ 240 em março e zero nos outros onze.

Isso não é detalhe contábil, é o ponto do módulo. O objetivo é previsibilidade de caixa: saber que março tem um pico. Diluir a anuidade em doze avos esconde exatamente a informação que você quer.

```java
public interface ProjecaoRecorrente {
    /** Ocorrências reais no intervalo, na competência correta. */
    List<Ocorrencia> ocorrenciasEntre(Competencia inicio, Competencia fim);

    /** Custo mensal equivalente. Informação secundária, para comparar
     *  assinaturas entre si. NUNCA usado em projeção de caixa. */
    Dinheiro custoMensalEquivalente(RecorrenteId id);
}
```

O comentário no segundo método não é decorativo. É exatamente o tipo de função que alguém usa no lugar errado seis meses depois.

### Materialização

Recorrente é **template**, não lançamento. Duas estratégias:

| Estratégia | Como | Trade-off |
|---|---|---|
| Materializar | Job mensal cria os `Gasto` do mês | Histórico real, mas job pode falhar e duplicar |
| Calcular sob demanda | Projeção resolve na leitura | Sem job, mas não registra o que de fato foi cobrado |

**Use as duas.** Projeção futura calcula sob demanda. Competência corrente materializa — porque você vai querer marcar que a assinatura de fato foi cobrada, corrigir valor que mudou, ou registrar que cancelou no meio do mês.

A materialização precisa ser **idempotente**: chave única em `(recorrente_id, competencia)` no banco. Job que roda duas vezes não pode gerar dois lançamentos.

---

## Horizonte de doze meses

A consolidação que dá sentido ao módulo inteiro. Responde: **quanto do meu dinheiro já está comprometido, e até quando.**

```java
public record MesHorizonte(
    Competencia competencia,
    Dinheiro rendaLiquidaPrevista,
    Dinheiro parcelasCartao,        // vem de cartoes
    Dinheiro recorrentes,
    Dinheiro gastosAVistaPrevistos,
    Dinheiro comprometidoTotal,
    Dinheiro sobra                  // renda − comprometido
) {}
```

```
GET /api/fluxo/horizonte?meses=12
```

A consulta cruza módulos: `fluxo` pergunta a `cartoes` o total de parcelas por competência. Interface fina e read-only:

```java
public interface ConsultaParcelas {
    Map<Competencia, Dinheiro> totalPorCompetencia(Competencia de, Competencia ate);
}
```

`cartoes` expõe isso na sua fachada pública. `fluxo` não conhece `Parcela`, `Fatura` nem `Cartao` — só recebe um mapa de competência para valor.

### Marcos que a resposta deve conter

- **Mês de queda relevante** — quando um parcelamento grande termina e o comprometimento cai
- **Mês de comprometimento zero** — se existir no horizonte
- **Picos** — meses onde parcelas e anuidades coincidem

Calcule no backend e devolva marcado. O front não deve derivar isso do array — é regra de negócio, e regra no front diverge.

---

## Fachada pública e eventos

O que outros módulos enxergam:

```java
public interface FluxoFacade {

    /** Usado pelo sistema (revisão da semana) e pela tela inicial. */
    ResumoMes resumoDoMes(Competencia c);   // total gasto, ganhos, sobra

    /** Usado pela agenda (vencimentos). */
    List<OcorrenciaPrevista> recorrentesPrevistos(LocalDate de, LocalDate ate);

    /** Usado pela captura rápida da agenda, sempre após sua confirmação. */
    GastoId registrarGasto(RegistrarGasto comando);
}
```

Eventos publicados:

| Evento | Quando | Dados |
|---|---|---|
| `GastoLancado` | Gasto persistido, à vista ou no crédito | `gastoId`, `data`, `valor`, `categoriaId` |
| `GanhoRegistrado` | Ganho persistido | `ganhoId`, `competencia`, `liquido` |

O `sistema` usa `GastoLancado` para cumprir a missão "lançar os gastos de ontem" e `resumoDoMes` para a revisão da semana.

> **Ponto em aberto:** a revisão da semana (documento 10) mostra "gasto × planejado", mas este módulo não tem orçamento por categoria. Duas saídas: comparar o mês corrente com a média dos 3 meses anteriores (zero modelagem nova), ou adicionar um orçamento simples por categoria. Decidir antes da fase 4. Até lá, o documento 10 assume a média dos 3 meses.

---

## Endpoints

```
PUT    /api/ganhos/{id}             ← ID do cliente, idempotente
GET    /api/ganhos?competencia=2026-09
GET    /api/ganhos/desconto-medio?tipo=SALARIO
POST   /api/ganhos/estimar          { bruto, tipo } → líquido estimado

PUT    /api/gastos/{id}             ← ID do cliente, idempotente
GET    /api/gastos?competencia=2026-09&categoria={id}
GET    /api/gastos/previa           ← chamado a cada tecla, sem efeito colateral
DELETE /api/gastos/{id}

GET    /api/categorias
POST   /api/categorias              ← criação inline
PATCH  /api/categorias/{id}         ← só desativa

POST   /api/recorrentes
GET    /api/recorrentes
PATCH  /api/recorrentes/{id}
GET    /api/recorrentes/{id}/ocorrencias?de=2026-09&ate=2027-08

GET    /api/fluxo/horizonte?meses=12
GET    /api/fluxo/resumo?competencia=2026-09
```

---

## Schema

```sql
CREATE SCHEMA fluxo;

CREATE TABLE fluxo.categorias (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    nome         TEXT NOT NULL UNIQUE,
    predefinida  BOOLEAN NOT NULL DEFAULT false,
    ativa        BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE fluxo.ganhos (
    id            UUID PRIMARY KEY,           -- vem do cliente, sem DEFAULT
    competencia   DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    descricao     TEXT NOT NULL,
    bruto         NUMERIC(19,2) NOT NULL CHECK (bruto >= 0),
    liquido       NUMERIC(19,2) NOT NULL CHECK (liquido >= 0),
    tipo          TEXT NOT NULL,
    conta_id      UUID NOT NULL REFERENCES contas.contas(id),
    recebido_em   DATE,
    CONSTRAINT liquido_nao_supera_bruto CHECK (liquido <= bruto)
);

CREATE TABLE fluxo.recorrentes (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    descricao      TEXT NOT NULL,
    valor          NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    categoria_id   UUID NOT NULL REFERENCES fluxo.categorias(id),
    periodicidade  TEXT NOT NULL CHECK (periodicidade IN ('MENSAL','ANUAL')),
    dia_cobranca   INT NOT NULL CHECK (dia_cobranca BETWEEN 1 AND 31),
    mes_cobranca   INT CHECK (mes_cobranca BETWEEN 1 AND 12),
    forma          TEXT NOT NULL,
    conta_id       UUID REFERENCES contas.contas(id),
    cartao_id      UUID,
    inicio         DATE NOT NULL,
    fim            DATE,
    ativa          BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT anual_exige_mes CHECK (
        periodicidade <> 'ANUAL' OR mes_cobranca IS NOT NULL)
);

CREATE TABLE fluxo.gastos (
    id                 UUID PRIMARY KEY,      -- vem do cliente, sem DEFAULT
    data               DATE NOT NULL,
    descricao          TEXT NOT NULL,
    valor              NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    categoria_id       UUID NOT NULL REFERENCES fluxo.categorias(id),
    forma              TEXT NOT NULL CHECK (forma IN ('A_VISTA','CREDITO')),
    conta_id           UUID REFERENCES contas.contas(id),   -- quando A_VISTA
    cartao_id          UUID,                                 -- quando CREDITO
    parcelas           INT CHECK (parcelas > 0),
    recorrente_id      UUID REFERENCES fluxo.recorrentes(id),
    competencia_recorrente DATE,                             -- quando materializado
    CONSTRAINT forma_coerente CHECK (
        (forma = 'CREDITO' AND cartao_id IS NOT NULL AND parcelas IS NOT NULL
                           AND conta_id IS NULL) OR
        (forma = 'A_VISTA' AND conta_id IS NOT NULL
                           AND cartao_id IS NULL AND parcelas IS NULL)),
    UNIQUE (recorrente_id, competencia_recorrente)
);

CREATE INDEX ON fluxo.gastos (data DESC);
CREATE INDEX ON fluxo.gastos (categoria_id);
CREATE INDEX ON fluxo.gastos (conta_id);
CREATE INDEX ON fluxo.ganhos (competencia DESC);
```

As constraints `forma_coerente` e `anual_exige_mes` são o tipo de regra que se pensa em deixar só na aplicação. Deixe também no banco: é a última linha de defesa contra um script de correção mal escrito.

`UNIQUE (recorrente_id, competencia_recorrente)` é a chave da materialização idempotente. Para gastos avulsos as duas colunas são nulas, e o Postgres não considera nulos iguais — não há conflito.

**Sobre `dia_cobranca` até 31.** Assinatura cobrada dia 31 em fevereiro precisa de regra. Convenção do sistema: **cobra no último dia do mês** quando o dia não existe. Documente e teste, porque é o tipo de coisa que só aparece em fevereiro.

---

## Testes

Casos de exemplo:

```java
@Test void liquido_maior_que_bruto_e_rejeitado()
@Test void desconto_medio_segrega_por_tipo_de_ganho()
@Test void gasto_no_credito_gera_parcelas_no_cartao()
@Test void gasto_no_credito_falha_sem_cartao()
@Test void gasto_a_vista_falha_sem_conta()
@Test void gasto_a_vista_debita_a_conta_informada()
@Test void gasto_no_credito_nao_debita_conta_no_ato()
@Test void gasto_enviado_duas_vezes_com_mesmo_id_nao_duplica()
@Test void gasto_lancado_publica_evento_apos_commit()
@Test void previa_de_gasto_nao_persiste_nada()
@Test void anuidade_aparece_so_no_mes_de_cobranca()
@Test void recorrente_dia_31_cobra_no_ultimo_dia_de_fevereiro()
@Test void materializacao_rodada_duas_vezes_nao_duplica()
@Test void categoria_desativada_some_da_selecao_mas_gasto_antigo_persiste()
@Test void horizonte_soma_parcelas_recorrentes_e_renda()
```

Propriedade que vale testar:

> A soma dos gastos à vista de uma competência mais as parcelas daquela competência mais os recorrentes daquela competência é sempre igual ao comprometido total daquele mês no horizonte.

Se essa identidade quebra, alguma coisa está sendo contada duas vezes ou nenhuma — e o bug mais provável é gasto no crédito sendo somado tanto como gasto quanto como parcela.
