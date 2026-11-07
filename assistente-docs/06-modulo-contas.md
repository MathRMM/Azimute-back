# 06 — Módulo `contas`

## Escopo

Contas reais onde o dinheiro fica: corrente, poupança, investimento, carteira. Movimentações entre elas, conciliação com extrato e projeção de saldo.

É módulo **fundacional**, não acessório. Sem ele, `fluxo`, `cartoes` e `reserva` registram valores que não estão em lugar nenhum — e nada do que o sistema calcula pode ser conferido contra a realidade.

---

## As duas camadas que não podem se misturar

Este é o conceito central do módulo, e errá-lo contamina o sistema inteiro.

| | Camada física | Camada de propósito |
|---|---|---|
| Pergunta | **Onde** o dinheiro está | **Para que** o dinheiro é |
| Modelo | `Conta` | `CategoriaReserva` |
| Verdade | Extrato bancário | Sua decisão |
| Soma | Patrimônio real | Distribuição do patrimônio |

Você guarda tudo num balde só e divide mentalmente em cinco categorias. Na prática, isso significa que R$ 12.000 de Emergência e R$ 9.000 de Moto podem estar no **mesmo CDB**. A conta tem R$ 21.000; as categorias somam R$ 21.000; e não existem R$ 42.000.

**Regra dura: nunca some saldo de conta com saldo de categoria.** São duas leituras do mesmo dinheiro. Qualquer tela que exiba "patrimônio total" usa a camada física e só ela.

A relação entre as duas é de **conciliação**, não de soma:

```
Σ saldo das contas marcadas como reserva  ==  Σ saldo das categorias de reserva
```

Quando essa igualdade quebra, alguma coisa não foi registrada. O app não conserta sozinho — ele avisa.

---

## Modelo

```java
public record Conta(
    ContaId id,
    String apelido,              // "Nubank", "Itaú corrente", "CDB Liquidez"
    String instituicao,
    TipoConta tipo,
    Funcao funcao,
    Dinheiro limiteChequeEspecial,   // ZERO se não houver
    boolean ativa
) {}

public enum TipoConta {
    CORRENTE, POUPANCA, INVESTIMENTO, CARTEIRA, BENEFICIO
}

public enum Funcao {
    GIRO,       // dia a dia: recebe salário, paga fatura, gasto no débito
    RESERVA     // guarda dinheiro planejado; concilia com as categorias
}
```

`Funcao` é o que torna a conciliação possível. Sem ela, o sistema não sabe quais contas deveriam bater com as categorias de reserva.

`CARTEIRA` é dinheiro em espécie. Parece dispensável e não é: gasto em dinheiro que não sai de conta nenhuma some da conciliação e vira divergência inexplicável três meses depois.

`BENEFICIO` cobre VR/VA. Tem saldo real, tem gasto real, e não se mistura com dinheiro livre — não dá para usar VR para pagar fatura.

---

## Movimentações

Toda mudança de saldo é uma movimentação. Quatro tipos principais, e a distinção entre eles é o que impede contagem dupla.

```java
public sealed interface Movimentacao {

    /** Ganho creditado numa conta. */
    record Entrada(ContaId destino, GanhoId ganho, Dinheiro valor, LocalDate data)
        implements Movimentacao {}

    /** Gasto à vista debitado de uma conta. */
    record Saida(ContaId origem, GastoId gasto, Dinheiro valor, LocalDate data)
        implements Movimentacao {}

    /** Dinheiro mudando de lugar. NÃO é gasto. */
    record Transferencia(ContaId origem, ContaId destino, Dinheiro valor,
                         LocalDate data, String motivo)
        implements Movimentacao {}

    /** Liquidação de fatura. NÃO é gasto. */
    record PagamentoFatura(ContaId origem, CartaoId cartao, Competencia fatura,
                           Dinheiro valor, LocalDate data)
        implements Movimentacao {}

    /** Rendimento de aplicação. Credita a conta; não é ganho de trabalho. */
    record Rendimento(ContaId destino, Dinheiro valor, LocalDate data)
        implements Movimentacao {}
}
```

### A armadilha da contagem dupla

Transferência e pagamento de fatura existem como tipos próprios para evitar o erro mais comum de app financeiro pessoal.

**Transferência não é gasto.** Mandar R$ 1.500 do Nubank para o CDB não empobrece ninguém. Se isso for lançado como saída, seu relatório de gastos do mês fica R$ 1.500 maior e a categorização perde o sentido.

**Pagamento de fatura não é gasto.** O gasto aconteceu quando você comprou — foi lançado em `fluxo`, categorizado, e entrou na fatura. Pagar a fatura é **liquidação**: move dinheiro da conta para quitar dívida já registrada. Lançar como gasto conta a mesma compra duas vezes.

Modelar como `sealed interface` obriga o compilador a exigir tratamento explícito de todos os casos em qualquer relatório. Um `switch` que soma "gastos do mês" não compila se esquecer de decidir o que fazer com `Transferencia`.

---

## Saldo: computado a partir de conciliação

A pergunta de projeto: o saldo é soma acumulada de movimentações, ou instantâneo que você atualiza?

**Soma acumulada pura não funciona.** Ela exige que *toda* movimentação seja registrada. Você vai esquecer um pix de R$ 30, e a partir daí o saldo calculado fica R$ 30 errado **para sempre**, sem nenhum mecanismo de correção.

**Instantâneo puro também não.** Perde o histórico e não permite projetar.

A solução é híbrida:

```java
public record Conciliacao(
    ConciliacaoId id,
    ContaId conta,
    LocalDate data,
    Dinheiro saldoReal,        // lido do extrato, por você
    Dinheiro saldoCalculado,   // o que o sistema achava
    Dinheiro divergencia,
    String justificativa
) {}

public Dinheiro saldoAtual(ContaId conta) {
    var ultima = conciliacaoRepo.ultima(conta);
    var movimentacoes = movRepo.desde(conta, ultima.data());
    return ultima.saldoReal().mais(soma(movimentacoes));
}
```

**A conciliação é o marco zero móvel.** Você abre o app do banco, digita o saldo que está lá, e o sistema:

1. Compara com o que calculava
2. Mostra a divergência
3. Adota o valor real como nova base
4. **Registra a divergência em vez de apagá-la**

O passo 4 é o que dá valor. Divergência recorrente de R$ 40 no mesmo dia do mês é uma assinatura que você não cadastrou. Divergência crescente é registro sistematicamente incompleto. Apagar silenciosamente destrói essa informação.

É a mesma ideia de fechar o caixa de uma loja no fim do dia: conta o dinheiro, compara com o que o sistema dizia, e anota a diferença em vez de fingir que não houve.

Sugestão de ritmo: conciliar contas de giro toda semana, contas de reserva uma vez por mês. Reserva quase não se move e a divergência dela seria alarmante. Esse ritmo pode virar missão do `sistema` (documento 10) como evolução.

---

## Saldo projetado — a funcionalidade que resolve o problema

Com várias contas e vários cartões vencendo em datas diferentes, a pergunta operacional real é:

> **A conta X vai ter dinheiro no dia 10, quando a fatura do cartão Y vencer?**

Manualmente isso é difícil, e é exatamente o que deixou de ser possível sem anotação.

```java
public record SaldoProjetado(
    ContaId conta,
    LocalDate data,
    Dinheiro saldoPrevisto,
    List<EventoPrevisto> eventos,
    boolean ficaNegativo,
    LocalDate primeiroDiaNegativo
) {}
```

```
GET /api/contas/{id}/projecao?dias=60
GET /api/contas/projecao?dias=60          ← todas de uma vez
```

A projeção consome:

| Origem | O que traz |
|---|---|
| `cartoes.proximosVencimentos` | Faturas a vencer nesta conta, com data nominal e valor |
| `fluxo` | Recorrentes debitados aqui, ganhos previstos |
| `reserva` | Aportes planejados que saem daqui |

**O alerta que importa:** o dia em que o saldo previsto fica negativo, e por causa de qual evento. Não é aviso genérico de "saldo baixo" — é "dia 10 a fatura do cartão Y de R$ 2.340 derruba a conta Nubank para −R$ 180".

Com essa informação, você transfere R$ 200 no dia 9 e o problema não existe. Sem ela, você paga cheque especial — que é a segunda linha de crédito mais cara do país, atrás do rotativo.

---

## Integração com os outros módulos

### Cartão tem conta de pagamento

`cartoes.cartoes.conta_pagamento` (ver `04-modulo-cartoes.md`). É o que permite a projeção saber de qual conta a fatura vai sair. Sem isso, o alerta de saldo negativo é impossível.

### Gasto à vista exige conta

`FormaPagamento.AVista(ContaId)` (ver `03-modulo-fluxo.md`). A forma de pagamento é atributo da conta, não do gasto: pix e débito saem da mesma conta corrente, e dinheiro sai da carteira.

Por isso `contas` é construído antes de `fluxo` (fase 3). Ao contrário, haveria gastos sem conta para migrar.

### Ganho tem conta de destino

`Ganho.contaDestino` gera a `Entrada` nesta conta.

### Aporte de reserva tem conta de destino

`Aporte.contaDestino` (ver `05-modulo-reserva.md`). É o que fecha o laço da conciliação: sem saber em qual conta o aporte caiu, não dá para verificar se a soma das categorias bate com a soma das contas de reserva.

### Diagrama de dependências

```
                 comum
                   ▲
                contas  ◄── fundacional
                   ▲
      ┌────────────┼────────────┐
      │            │            │
    fluxo ──►  cartoes      reserva
```

`contas` não conhece nenhum dos três. Os três a conhecem. A projeção de saldo consulta as fachadas de `cartoes`, `fluxo` e `reserva` por interfaces que o próprio `contas` define (`FontesDeProjecao`), implementadas por eles — assim a seta de dependência continua apontando para `contas`.

---

## Fachada pública e eventos

```java
public interface ContasFacade {
    Dinheiro saldoAtual(ContaId conta);
    void registrarEntrada(ContaId conta, GanhoId g, Dinheiro v, LocalDate d);
    void registrarSaida(ContaId conta, GastoId g, Dinheiro v, LocalDate d);
    void registrarPagamentoFatura(ContaId conta, CartaoId c, Competencia f,
                                  Dinheiro v, LocalDate d);
    List<Conta> contasDeReserva();
}
```

`contasDeReserva()` serve à conciliação cruzada: `reserva` pergunta quais contas deveriam somar o total das categorias.

Evento publicado:

| Evento | Quando | Dados |
|---|---|---|
| `ConciliacaoFeita` | Conciliação registrada | `contaId`, `data`, `divergencia` |

---

## Endpoints

```
GET    /api/contas
POST   /api/contas
PATCH  /api/contas/{id}
GET    /api/contas/{id}/saldo
GET    /api/contas/{id}/extrato?de=2026-09-01&ate=2026-09-30

POST   /api/contas/transferencias
POST   /api/contas/{id}/rendimentos
POST   /api/contas/{id}/conciliacoes      { data, saldoReal, justificativa }
GET    /api/contas/{id}/conciliacoes      ← histórico de divergências

GET    /api/contas/projecao?dias=60
GET    /api/contas/consolidado            ← patrimônio real, camada física
GET    /api/contas/conciliacao-reserva    ← contas de reserva × categorias
```

`consolidado` soma **apenas** contas. Nunca inclui categoria de reserva.

`conciliacao-reserva` devolve os dois totais e a diferença:

```json
{ "totalContasReserva": 21000.00,
  "totalCategorias": 20850.00,
  "divergencia": 150.00,
  "possivelCausa": "Rendimento não registrado desde a última conciliação" }
```

**Rendimento é a causa mais provável de divergência positiva** numa conta de reserva. O CDB rende e ninguém lança. Por isso o fluxo próprio `POST /rendimentos`: credita a conta e distribui pelas categorias na proporção dos saldos atuais.

---

## Schema

```sql
CREATE SCHEMA contas;

CREATE TABLE contas.contas (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
    apelido                 TEXT NOT NULL UNIQUE,
    instituicao             TEXT NOT NULL,
    tipo                    TEXT NOT NULL CHECK (tipo IN
                            ('CORRENTE','POUPANCA','INVESTIMENTO','CARTEIRA','BENEFICIO')),
    funcao                  TEXT NOT NULL CHECK (funcao IN ('GIRO','RESERVA')),
    limite_cheque_especial  NUMERIC(19,2) NOT NULL DEFAULT 0,
    ativa                   BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE contas.movimentacoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    tipo            TEXT NOT NULL CHECK (tipo IN
                    ('ENTRADA','SAIDA','TRANSFERENCIA','PAGAMENTO_FATURA','RENDIMENTO')),
    conta_origem    UUID REFERENCES contas.contas(id),
    conta_destino   UUID REFERENCES contas.contas(id),
    valor           NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    data            DATE NOT NULL,
    ganho_id        UUID,
    gasto_id        UUID,
    cartao_id       UUID,
    fatura_comp     DATE,
    motivo          TEXT,

    CONSTRAINT direcao_coerente CHECK (
        (tipo = 'ENTRADA'          AND conta_destino IS NOT NULL AND conta_origem IS NULL) OR
        (tipo = 'RENDIMENTO'       AND conta_destino IS NOT NULL AND conta_origem IS NULL) OR
        (tipo = 'SAIDA'            AND conta_origem  IS NOT NULL AND conta_destino IS NULL) OR
        (tipo = 'PAGAMENTO_FATURA' AND conta_origem  IS NOT NULL AND conta_destino IS NULL) OR
        (tipo = 'TRANSFERENCIA'    AND conta_origem  IS NOT NULL AND conta_destino IS NOT NULL
                                   AND conta_origem <> conta_destino)
    )
);

CREATE TABLE contas.conciliacoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    conta_id        UUID NOT NULL REFERENCES contas.contas(id),
    data            DATE NOT NULL,
    saldo_real      NUMERIC(19,2) NOT NULL,
    saldo_calculado NUMERIC(19,2) NOT NULL,
    divergencia     NUMERIC(19,2) NOT NULL,
    justificativa   TEXT,
    UNIQUE (conta_id, data)
);

-- Divergência é fato do momento da conciliação (ver 00-arquitetura)
CREATE TRIGGER conciliacoes_imutaveis
    BEFORE UPDATE OR DELETE ON contas.conciliacoes
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();

CREATE INDEX ON contas.movimentacoes (conta_origem, data DESC);
CREATE INDEX ON contas.movimentacoes (conta_destino, data DESC);
CREATE INDEX ON contas.conciliacoes (conta_id, data DESC);
```

`direcao_coerente` é a constraint mais importante do módulo. Ela torna impossível, no nível do banco, uma transferência sem destino ou uma saída com destino — o tipo de dado corrompido que produz saldo errado sem erro visível.

`divergencia` guardada como coluna, não calculada: é fato histórico do momento da conciliação, e recalcular depois com movimentações lançadas retroativamente daria outro número. A justificativa errada se corrige com uma nova conciliação, não com `UPDATE`.

---

## Fases

`contas` é fundacional, então entra cedo — antes de `fluxo`, para evitar migração de dados. A ordem completa, incluindo os módulos do assistente, está no `00-arquitetura.md`.

| Fase | Entrega deste módulo |
|---|---|
| 3 | Contas, movimentações, conciliação |
| 7 | Saldo projetado e alerta de negativo; evento `ConciliacaoFeita` |

A fase 7 vem depois porque a projeção precisa saber quais faturas e recorrentes debitam cada conta.

---

## Testes

```java
// Contagem dupla — os mais importantes
@Test void transferencia_nao_aparece_como_gasto_no_relatorio()
@Test void pagamento_de_fatura_nao_aparece_como_gasto()
@Test void transferencia_nao_altera_patrimonio_consolidado()
@Test void compra_no_credito_nao_debita_conta_no_ato()

// Conciliação
@Test void saldo_parte_da_ultima_conciliacao_mais_movimentacoes()
@Test void conciliacao_registra_divergencia_em_vez_de_apagar()
@Test void conciliacao_nova_vira_a_base_do_calculo()
@Test void movimentacao_retroativa_nao_altera_divergencia_registrada()
@Test void update_em_conciliacao_e_rejeitado_pelo_banco()

// Projeção
@Test void projecao_identifica_dia_que_saldo_fica_negativo()
@Test void projecao_aponta_qual_evento_causou_o_negativo()
@Test void projecao_soma_faturas_recorrentes_e_ganhos_previstos()

// Camadas
@Test void consolidado_nao_inclui_categoria_de_reserva()
@Test void divergencia_entre_contas_reserva_e_categorias_e_reportada()
@Test void rendimento_credita_conta_e_distribui_nas_categorias()

// Integridade
@Test void transferencia_para_a_mesma_conta_e_rejeitada()
@Test void saida_com_conta_destino_e_rejeitada_pelo_banco()
```

O `movimentacao_retroativa_nao_altera_divergencia_registrada` protege a única coluna calculada persistida do módulo. É o tipo de coisa que alguém "normaliza" depois, e o histórico de divergências perde o sentido.
