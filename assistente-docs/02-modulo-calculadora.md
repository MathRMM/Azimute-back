# 02 — Módulo `calculadora`

## Escopo

Matemática financeira e histórico de simulações. O módulo tem duas metades com naturezas opostas:

```
calculadora/
├── core/          função pura, sem I/O, sem Spring, sem banco
│                  ESPELHADA no cliente Kotlin — roda offline
└── historico/     persistência de simulações salvas
                   só backend, exige rede
```

**`core` roda no cliente.** Calcular a taxa de uma proposta não depende de nada armazenado no servidor — são quatro números na tela e uma fórmula que não muda. Mandar isso pela rede é latência sem contrapartida, e você vai usar essa tela em pé numa concessionária, onde o sinal é ruim.

**`historico` exige rede, e tudo bem.** Salvar é ação deliberada e menos frequente que calcular. Se estiver offline no momento, a simulação entra na fila local do app e sobe depois.

Essa separação é o que permite a promessa central do módulo: **calcular sempre funciona; salvar funciona quando dá.**

É o módulo mais isolado do sistema e o primeiro a ser construído. Sozinho ele já resolve um problema real: descobrir a taxa embutida numa proposta de financiamento enquanto você está na concessionária.

---

## As quatro operações

```java
public final class JurosCompostos {

    /** Quanto um valor vira após n meses rendendo i ao mês. */
    public static Dinheiro montante(Dinheiro pv, TaxaMensal i, int n);

    /** Parcela de um financiamento (Price). */
    public static Dinheiro parcela(Dinheiro pv, TaxaMensal i, int n);

    /** Quanto vale hoje um fluxo de n parcelas. */
    public static Dinheiro valorPresente(Dinheiro pmt, TaxaMensal i, int n);

    /** A taxa embutida numa proposta. Sem solução fechada. */
    public static TaxaMensal taxaImplicita(Dinheiro pv, Dinheiro pmt, int n);
}
```

`TaxaMensal` é um tipo próprio, não `BigDecimal` solto:

```java
public record TaxaMensal(BigDecimal fracao) {
    public TaxaMensal {
        if (fracao.signum() < 0)
            throw new IllegalArgumentException("Taxa negativa: " + fracao);
    }
    public static TaxaMensal dePercentual(String pct) { /* "3.51" -> 0.0351 */ }
    public BigDecimal percentual()  { return fracao.multiply(CEM); }
    public TaxaMensal anualizada()  { /* (1+i)^12 - 1 */ }
}
```

O tipo evita o erro mais comum do domínio: passar `3.51` onde se esperava `0.0351`. Já vi isso custar um dígito decimal inteiro numa negociação.

---

## `taxaImplicita` — a operação que importa

É a que responde ao seu caso real. Você tem valor financiado, valor da parcela e prazo; quer a taxa.

A equação é:

```
PV = PMT × [1 − (1+i)^−n] / i
```

Não tem solução algébrica para `i`. É raiz de polinômio de grau n, resolvida numericamente.

### Use bisseção, não Newton-Raphson

Newton converge mais rápido — quadrático contra linear. E diverge quando a derivada fica próxima de zero, o que acontece em fluxos com taxa muito baixa. Você usaria isso em pé numa concessionária, com pressa: um resultado errado é pior que um resultado 30 ms mais lento.

Bisseção no intervalo `[1e-6, 0.30]` — de 0,0001% a 30% ao mês, cobrindo de poupança a agiotagem — converge com precisão de 1e-10 em cerca de 48 iterações. Microssegundos, e **nunca** diverge, desde que a função troque de sinal no intervalo. Ela troca sempre que `PMT × n > PV`, que é a condição de existir juros.

```java
public static TaxaMensal taxaImplicita(Dinheiro pv, Dinheiro pmt, int n) {
    if (pmt.valor().multiply(BigDecimal.valueOf(n)).compareTo(pv.valor()) <= 0)
        throw new SemJurosException("Total das parcelas não supera o financiado");

    BigDecimal baixo = new BigDecimal("0.000001");
    BigDecimal alto  = new BigDecimal("0.30");

    for (int k = 0; k < 200; k++) {
        BigDecimal meio = baixo.add(alto).divide(DOIS, MC);
        BigDecimal vp = valorPresenteBruto(pmt.valor(), meio, n);

        if (vp.subtract(pv.valor()).abs().compareTo(TOLERANCIA) < 0)
            return new TaxaMensal(meio);

        if (vp.compareTo(pv.valor()) > 0) baixo = meio; else alto = meio;
    }
    throw new NaoConvergiuException(...);
}
```

**Nota sobre `MathContext`.** Divisão de `BigDecimal` sem escala definida lança `ArithmeticException` em dízima periódica. Use `MathContext.DECIMAL64` nos cálculos intermediários e só arredonde para 2 casas no resultado final. Arredondar no meio do laço destrói a convergência.

### Casos de teste obrigatórios

Vieram de propostas reais e são a régua do módulo:

| PV | PMT | n | Taxa mensal | Taxa anual |
|---|---|---|---|---|
| 17.430,00 | 860,69 | 36 | 3,51% | 51,3% |
| 13.990,00 | 930,42 | 24 | 4,14% | 62,6% |

As taxas anuais de 51,4% e 62,7% que apareceram nas primeiras conversas vieram de arredondamento intermediário. Recalculadas com precisão decimal, dão 51,3% e 62,6%. Esses são os valores esperados nos testes.

Mais os degenerados: `n = 1`, `PMT × n == PV` (deve lançar `SemJurosException`), `PV` zero, `PMT` zero, `n` negativo.

E uma propriedade que vale mais que os exemplos:

> Para qualquer `pv`, `i` e `n` válidos: `taxaImplicita(pv, parcela(pv, i, n), n) ≈ i`, com tolerância de 1e-8.

Ida e volta. Se essa propriedade passa para milhares de combinações aleatórias, as duas funções estão certas.

---

## A comparação que vale a viagem

```java
public record Veredito(
    Dinheiro custoTotalFinanciado,
    Dinheiro custoTotalAVista,
    Dinheiro rendimentoDoDinheiroGuardado,
    Dinheiro delta,              // positivo = à vista ganha
    TaxaMensal taxaFinanciamento,
    TaxaMensal taxaRendimento
) {}

public static Veredito compararAVistaVsFinanciado(
    Dinheiro precoAVista,
    Dinheiro entrada,
    Dinheiro parcela,
    int prazo,
    TaxaMensal rendimento
);
```

A pergunta real na concessionária não é "a taxa é alta". É: **se eu financiar e deixar meu dinheiro rendendo, eu saio na frente ou atrás?**

Com financiamento a 4,14% ao mês e CDB a 0,8%, a resposta é obviamente atrás. Mas se um dia te oferecerem 0,6% ao mês — e existe, em taxa promocional de montadora — a conta inverte, e você quer saber disso na hora.

**Retorne o delta em reais, não um booleano.** "Financiar custa R$ 8.340 a mais" é argumento de negociação. "Não vale a pena" não é.

---

## Sobre o CET

Não calcule CET e não chame de CET o que você calcula.

O Custo Efetivo Total tem definição normativa do Banco Central e inclui tarifas, seguro prestamista, IOF e registro de contrato — coisas que não estão no fluxo de parcelas que você observa. O que `taxaImplicita` devolve é a **taxa efetiva do fluxo de caixa**: se as tarifas foram embutidas nas parcelas, ela já as captura; se são cobradas à parte, não.

Na prática, para financiamento de veículo com tudo embutido, os dois números ficam próximos. Mas rotular como CET um número que pode divergir do contrato cria confusão exatamente no momento em que você precisa de clareza.

Chame de **taxa efetiva das parcelas**, e no front deixe explícito que ela deriva do fluxo informado.

---

## Sensibilidade à entrada

O cliente tem um controle deslizante que varia a entrada e recalcula a parcela ao vivo, segurando a taxa apurada. A operação é composição das anteriores:

```java
public record PontoSensibilidade(
    Dinheiro entrada,
    Dinheiro financiado,
    Dinheiro parcela,
    Dinheiro totalPago,
    Dinheiro totalJuros,
    BigDecimal distanciaDaAncora   // 0 = ponto real; cresce com a extrapolação
) {}

public static List<PontoSensibilidade> curvaPorEntrada(
    Dinheiro valorBem, Dinheiro entradaAncora, TaxaMensal taxa, int prazo, int passos);
```

Roda inteiramente no cliente. Não há endpoint.

**Como a curva é gerada** (decisão T7 do SPEC, novembro de 2026):

- `passos` pontos igualmente espaçados, com entrada de R$ 0 até o **valor do bem inteiro**. O último ponto é o cenário à vista — parcela e juros zero — e não é erro: é o alvo.
- O ponto exato da âncora (a entrada da proposta real) sempre entra na curva, uma vez só.
- `distanciaDaAncora = |entrada − entradaAncora| ÷ valorBem`, com 4 casas. Vale 0 na âncora e cresce com a extrapolação, sempre entre 0 e 1. Dividir pelo valor do bem deixa a medida na mesma escala para uma moto de R$ 20 mil e um carro de R$ 200 mil; a interface decide a partir de que distância esmaecer o número.

### A premissa que precisa estar visível

Segurar a taxa constante enquanto a entrada varia é **aproximação local**, e ela erra nos dois sentidos:

| Efeito | Direção | Motivo |
|---|---|---|
| Taxa melhora com entrada maior | Estimativa fica **pessimista** | Menor risco para a financeira |
| Tarifas fixas pesam mais sobre financiado menor | Estimativa fica **otimista** | Cadastro, IOF e registro não escalam |

Os dois não se cancelam de forma previsível. Por isso `distanciaDaAncora` existe no registro: é o insumo para a interface degradar a confiança visual conforme se afasta do único ponto onde o número é fato.

Não tente modelar a variação da taxa com a entrada. Exigiria conhecer a política de crédito da financeira, que não é pública e muda. Uma aproximação honesta e declarada vale mais que um modelo inventado.

### Quando vale antecipar

A pergunta que o slider responde de verdade é "vale entrar com o que tenho em algum momento?". Uma operação futura do `core`, ainda não especificada em detalhe:

```java
public static LocalDate dataDeEquilibrio(
    SortedMap<Competencia, Dinheiro> saldoProjetado,  // vem da projeção da reserva
    TaxaMensal taxa,
    Dinheiro parcelaMaxima,       // o que já seria poupado por mês
    Dinheiro beneficioMensal);    // quanto o bem economiza ou custa por mês
```

Para cada data candidata: financia a diferença, com parcela limitada ao que já seria poupado, e calcula `juros totais / meses antecipados`. A partir da data em que esse custo fica abaixo do benefício mensal, antecipar compensa. O benefício mensal é um dado que só você tem.

---

## Duas implementações, um contrato

O `core` existe duas vezes: Java no backend, Kotlin no cliente. O risco real é divergirem — `MathContext` diferente, tolerância diferente, arredondamento em ponto diferente do laço. Aí a mesma proposta dá 4,14% num lugar e 4,1398% no outro, e o histórico salvo não bate com a tela.

### Vetores de ouro

Um arquivo versionado no repositório, consumido pelos testes das **duas** implementações:

```json
// core/src/test/resources/vetores-juros.json
[
  { "caso": "moto_36x_proposta_real",
    "pv": "17430.00", "pmt": "860.69", "n": 36,
    "taxaMensal": "0.03510", "toleranciaTaxa": "0.00001",
    "totalPago": "30984.84", "totalJuros": "13554.84" },

  { "caso": "moto_24x_proposta_real",
    "pv": "13990.00", "pmt": "930.42", "n": 24,
    "taxaMensal": "0.04140", "toleranciaTaxa": "0.00001",
    "totalPago": "22330.08", "totalJuros": "8340.08" },

  { "caso": "celular_10x", "pv": "2400.00", "pmt": "279.00", "n": 10,
    "taxaMensal": "0.02840", "toleranciaTaxa": "0.0001" },

  { "caso": "divisao_inexata", "pv": "100.00", "pmt": "35.00", "n": 3, ... },
  { "caso": "prazo_unitario",  "pv": "1000.00", "pmt": "1010.00", "n": 1, ... },
  { "caso": "taxa_muito_baixa", "pv": "10000.00", "pmt": "1005.00", "n": 10, ... }
]
```

O arquivo é a fonte de verdade. Qualquer mudança em qualquer das duas implementações que quebre um vetor quebra o build. Adicionar caso novo é adicionar linha no JSON, e as duas suítes passam a cobri-lo automaticamente.

Sem isso, a duplicação é aposta. Com isso, é contrato verificado.

### Alternativa: módulo Kotlin Multiplatform

Se o cliente é Compose Multiplatform, existe a opção de escrever o `core` **uma vez só**, em Kotlin, como módulo KMP:

| | Duas implementações + vetores | Módulo KMP compartilhado |
|---|---|---|
| Divergência | Possível, travada por teste | Impossível |
| Build | Simples | Mais complexo (targets, publicação) |
| Backend | Java puro | Passa a ter dependência Kotlin |
| Esforço inicial | Baixo | Médio |

Como o backend já está definido como Java 25 e o JVM consome artefato Kotlin sem atrito, a segunda opção é viável e mais correta em princípio. Mas ela mistura duas linguagens no backend por um módulo de ~200 linhas.

**Recomendo começar com vetores de ouro.** Se a divergência aparecer na prática mais de uma vez, migre para KMP — os vetores continuam válidos e viram os testes do módulo compartilhado.

---

## Histórico de simulações

A parte que exige backend. O propósito é negociação: você vai visitar mais de uma concessionária, receber mais de uma proposta pelo mesmo bem, e vai querer comparar depois — não de memória.

```java
public record SimulacaoSalva(
    SimulacaoId id,              // gerado no CLIENTE (ver idempotência)
    String bem,                  // "Honda CB 300F"
    LocalDate data,
    String local,                // "Concessionária X, Campinas"
    String instituicao,          // "Banco Y" / "Financeira da montadora"

    // entrada informada
    Dinheiro valorBem,
    Dinheiro entrada,
    Dinheiro parcela,
    int prazo,

    // saída calculada — SNAPSHOT, nunca recalculado
    Dinheiro valorFinanciado,
    TaxaMensal taxaMensal,
    BigDecimal taxaAnual,
    Dinheiro totalPago,
    Dinheiro totalJuros,

    OrigemSimulacao origem,      // PROPOSTA ou ESTIMATIVA
    SimulacaoId ancora,          // obrigatório se ESTIMATIVA
    String observacao,
    Instant calculadaEm,
    Instant sincronizadaEm       // null enquanto na fila local
) {}
```

### A saída é snapshot, não fórmula

Mesmo princípio da curva de meta congelada e da fatura paga: **o que foi salvo não se recalcula na leitura.**

Se daqui a um ano você ajustar a tolerância da bisseção ou trocar o modo de arredondamento, uma proposta salva em 2026 precisa continuar mostrando exatamente o número que você viu na tela naquele dia. Recalcular na leitura faz o histórico se mexer sozinho, e histórico que se mexe não serve para nada. O trigger seletivo descrito no `00-arquitetura.md` garante isso no banco.

### Comparação entre propostas

A consulta que dá sentido ao histórico:

```
GET /api/calculadora/historico/comparar?bem=Honda%20CB%20300F

→ { "bem": "Honda CB 300F",
    "propostas": [
      { "instituicao": "Financeira da montadora", "taxaMensal": 3.20,
        "totalJuros": 9840.00, "data": "2026-09-14", "melhor": true },
      { "instituicao": "Banco Y", "taxaMensal": 4.14,
        "totalJuros": 13554.84, "data": "2026-09-12" }
    ],
    "economiaEntreMelhorEPior": 3714.84 }
```

O campo `economiaEntreMelhorEPior` em reais é o que você leva para a mesa de negociação. "A taxa de vocês é maior" é opinião; "a proposta de vocês custa R$ 3.714 a mais" é fato.

Agrupamento por `bem` como texto livre, sem entidade de negociação. Você não precisa cadastrar a moto antes de simular — na concessionária, cadastrar qualquer coisa é atrito.

---

## Sincronização offline

Você calcula sem rede e pode querer salvar. A gravação entra na fila local do app (o componente `sync`, compartilhado com os outros módulos — ver `00-arquitetura.md`) e sobe quando houver conexão.

### Idempotência

**O ID é gerado no cliente**, como UUIDv7. Foi aqui que a exceção nasceu; hoje ela vale para tudo que pode ser criado offline.

O motivo: rede ruim produz o pior caso possível — a requisição chega ao servidor, o servidor grava, a resposta se perde, o cliente acha que falhou e tenta de novo. Sem ID do cliente, isso vira duas simulações idênticas no histórico. Com ID do cliente, a segunda tentativa é reconhecida como repetição.

```
PUT /api/calculadora/historico/{id}
```

`PUT` com ID fornecido, não `POST`. Idempotente por definição do método: repetir a chamada com o mesmo ID e o mesmo corpo não cria nada novo.

```java
public SimulacaoSalva salvar(SimulacaoId id, DadosSimulacao dados) {
    var existente = repo.buscar(id);
    if (existente.isPresent()) return existente.get();   // repetição, não erro
    return repo.inserir(id, dados);
}
```

Retorna 200 na repetição, não 409. Do ponto de vista do cliente, "já estava lá" e "acabei de gravar" têm o mesmo desfecho.

### A fila no cliente

Responsabilidade do app Kotlin, mas o contrato importa aqui:

1. Salvar grava primeiro no armazenamento local, com `sincronizadaEm = null`
2. A UI mostra a simulação imediatamente, marcada como pendente
3. Um worker tenta enviar quando há rede
4. Sucesso marca `sincronizadaEm`; falha mantém na fila
5. Sem limite de tentativas e sem descarte automático — dado de negociação não se joga fora por erro de rede

O item 2 é o que preserva a sensação de que salvar funcionou. Bloquear a UI esperando rede numa concessionária é exatamente o cenário que a arquitetura deveria evitar.

---

## Endpoints

```
PUT    /api/calculadora/historico/{id}        ← idempotente, ID do cliente
GET    /api/calculadora/historico
GET    /api/calculadora/historico?bem=...
GET    /api/calculadora/historico/comparar?bem=...
PATCH  /api/calculadora/historico/{id}        ← só observação e status
DELETE /api/calculadora/historico/{id}
```

**Não há endpoint de cálculo.** Cálculo acontece no cliente. Se um dia surgir um segundo consumidor da API que não queira reimplementar a matemática, adicione — mas não antes de existir esse consumidor.

`PATCH` limitado a campos anotativos. Valores de entrada e saída de uma simulação salva são imutáveis: se a proposta mudou, é outra proposta, e vira registro novo. Editar valores destruiria a comparação histórica.

---

## Schema

```sql
CREATE SCHEMA calculadora;

CREATE TABLE calculadora.simulacoes (
    id                UUID PRIMARY KEY,          -- vem do cliente, sem DEFAULT
    bem               TEXT NOT NULL,
    data              DATE NOT NULL,
    local             TEXT,
    instituicao       TEXT,

    valor_bem         NUMERIC(19,2) NOT NULL CHECK (valor_bem > 0),
    entrada           NUMERIC(19,2) NOT NULL CHECK (entrada >= 0),
    parcela           NUMERIC(19,2) NOT NULL CHECK (parcela > 0),
    prazo             INT NOT NULL CHECK (prazo > 0),

    valor_financiado  NUMERIC(19,2) NOT NULL,
    taxa_mensal       NUMERIC(12,8) NOT NULL,
    taxa_anual        NUMERIC(12,8) NOT NULL,
    total_pago        NUMERIC(19,2) NOT NULL,
    total_juros       NUMERIC(19,2) NOT NULL,

    observacao        TEXT,
    origem            TEXT NOT NULL DEFAULT 'PROPOSTA'
                      CHECK (origem IN ('PROPOSTA','ESTIMATIVA')),
    ancora_id         UUID REFERENCES calculadora.simulacoes(id),
    status            TEXT NOT NULL DEFAULT 'RECEBIDA'
                      CHECK (status IN ('RECEBIDA','DESCARTADA','ACEITA')),
    calculada_em      TIMESTAMPTZ NOT NULL,
    sincronizada_em   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT entrada_nao_supera_bem CHECK (entrada < valor_bem),
    CONSTRAINT estimativa_exige_ancora CHECK (
        origem <> 'ESTIMATIVA' OR ancora_id IS NOT NULL)
);

CREATE INDEX ON calculadora.simulacoes (bem, taxa_mensal);
CREATE INDEX ON calculadora.simulacoes (data DESC);

-- Valores são snapshot (ver 00-arquitetura, triggers de imutabilidade)
CREATE TRIGGER simulacoes_valores_imutaveis
    BEFORE UPDATE ON calculadora.simulacoes
    FOR EACH ROW EXECUTE FUNCTION simulacao_valores_imutaveis();
```

`id UUID PRIMARY KEY` **sem** `DEFAULT uuidv7()` — o ID vem do cliente. O comentário no DDL evita que alguém "conserte" isso depois.

`origem` separa **proposta** de **estimativa**. Uma proposta é número que alguém te ofereceu; uma estimativa saiu do slider segurando a taxa de outra proposta. As duas são úteis salvar — a estimativa registra o cenário que você quer buscar na próxima negociação — mas confundi-las contamina a comparação. `estimativa_exige_ancora` garante que toda estimativa aponte para a proposta de onde a taxa veio.

Consequência: a consulta de comparação filtra `origem = 'PROPOSTA'` por padrão. Comparar sua própria extrapolação com uma oferta real e concluir que a oferta é ruim seria comparar com um número que ninguém prometeu.

`taxa_mensal NUMERIC(12,8)` guarda a fração com oito casas: 0,03510000. Precisão suficiente para reconstruir a comparação sem erro visível.

`calculada_em` e `sincronizada_em` são distintos de propósito. A diferença entre os dois é quanto tempo a simulação ficou na fila offline — e se for grande, é sinal de que a fila está falhando.

---

## O que este módulo não faz

- **Não calcula SAC.** Financiamento de veículo no Brasil é Price na prática. Adicione se aparecer necessidade real.
- **Não considera IOF, seguro ou tarifa isoladamente.** Lê o fluxo que você informar. Se as tarifas estão embutidas nas parcelas, a taxa já as captura.
- **Não conversa com `fluxo`, `cartoes` ou `reserva`.** Uma simulação salva é registro de negociação, não compromisso financeiro. Se você fechar o negócio, o gasto é lançado à parte.

O último ponto merece nota: seria tentador transformar uma simulação aceita em parcelas de cartão ou em gasto recorrente automaticamente. Não faça. Financiamento de veículo não passa em cartão, e o que você simulou raramente é idêntico ao que o contrato traz — tarifa de cadastro, seguro prestamista e registro aparecem só na assinatura.

---

## Testes

```java
@Test void taxa_implicita_reproduz_as_propostas_reais()        // vetores de ouro
@Test void ida_e_volta_parcela_taxa_implicita()                  // propriedade
@Test void total_igual_ao_financiado_lanca_sem_juros()
@Test void comparacao_devolve_delta_em_reais()
@Test void salvar_duas_vezes_com_mesmo_id_nao_duplica()
@Test void valores_de_simulacao_salva_nao_mudam()                // trigger
@Test void observacao_e_status_podem_mudar()
@Test void estimativa_sem_ancora_e_rejeitada()
@Test void comparacao_ignora_estimativas_por_padrao()
```
