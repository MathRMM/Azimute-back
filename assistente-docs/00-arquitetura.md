# 00 — Arquitetura geral

## Contexto

Assistente pessoal do dia a dia, usuário único. Começou como sistema de gestão financeira e cresceu para três papéis:

- **Contador:** controle de fluxo (ganhos e gastos), cartões de crédito, contas reais, reserva planejada por percentuais e uma calculadora de financiamento autônoma
- **Treinador:** plano de treino progressivo e acompanhamento de saúde
- **Secretária:** agenda integrada ao Google Agenda, vencimentos e captura rápida

Por cima de tudo, o **Sistema**: missões do dia, consistência e revisão da semana (documento 10).

O backend concentra a regra de negócio. O front é interface: renderiza, coleta entrada, chama endpoint.

A regra precisa ser enunciada com precisão, porque a versão preguiçosa dela ("nenhum cálculo no cliente") está errada:

> **Cálculo que depende de estado armazenado ou de regra que muda com o tempo fica no backend. Matemática pura pode ser espelhada no cliente.**

O que o critério separa:

| Fica no backend | Pode ir no cliente |
|---|---|
| Distribuição de aporte — depende do plano vigente e dos saldos | Juros compostos — depende só dos números na tela |
| Fatura e parcelamento — depende do dia de fechamento cadastrado | Taxa implícita de um fluxo — é raiz de polinômio |
| Horizonte de 12 meses — agrega dados de três módulos | Conversão mensal/anual de taxa |
| Desconto médio de ganho — depende do histórico | Total pago e total de juros de uma proposta |

A coluna da esquerda muda: você reorganizou percentuais sete vezes, e um cartão novo tem fechamento diferente. A coluna da direita não muda — a fórmula de Price é a mesma desde o século XVIII.

**Consequência prática:** a calculadora de financiamento roda inteiramente no cliente, sem rede. Você vai usá-la em pé numa concessionária, onde o sinal é ruim e a pressa é real. Round-trip para dividir dois números é latência sem contrapartida.

O backend entra só quando você quiser **salvar** o resultado. Detalhes em `02-modulo-calculadora.md`.

O risco dessa exceção é implementação duplicada divergindo entre Java e Kotlin. Ele é gerenciado com vetores de teste compartilhados, descritos no documento da calculadora — não com fé.

**A calculadora de financiamento é a única exceção de cálculo.** Distribuição de aporte, composição de fatura, geração de parcela, horizonte de doze meses, conciliação, projeção de saldo, missões e consistência rodam todos no backend, sem espelho no cliente.

Existe uma segunda coisa que o cliente faz além de interface, e ela não é cálculo: **coleta**. Na fase 16, o app lê o Health Connect e envia os dados brutos ao backend (documento 09). Nenhuma regra de negócio vai junto.

### A terceira camada: o banco

Existe um terceiro lugar onde lógica pode morar, e ele tem função própria:

| Camada | O que vive aqui | Critério |
|---|---|---|
| **Cliente** | Matemática pura, sem estado; coleta de dados do aparelho | Não depende de nada armazenado |
| **Backend** | Regra de negócio | Muda com o tempo; precisa de teste e deploy |
| **Banco** | Invariantes | Deve valer mesmo se o backend estiver errado |

O banco não calcula. Ele **impede estados impossíveis**.

Isso já aparece nas constraints espalhadas pelos módulos — `forma_coerente`, `direcao_coerente`, `liquido_nao_supera_bruto`. Trigger é a extensão natural disso para regras que `CHECK` não expressa.

#### Triggers de imutabilidade

Seis tabelas do sistema guardam fatos históricos que não devem mudar depois de gravados. Isso deve depender do banco, não da disciplina do código de aplicação:

| Tabela | Motivo |
|---|---|
| `reserva.planos` e `reserva.plano_alocacoes` | Plano é versionado, nunca editado |
| `cartoes.faturas_pagas` | Fatura paga é fato consumado |
| `calculadora.simulacoes` (colunas de valor) | Proposta salva é snapshot |
| `contas.conciliacoes` | Divergência é fato do momento da conciliação |
| `treino.planos` | Plano de treino é versionado, como o da reserva |
| `sistema.revisoes` | A revisão é o retrato da semana naquele momento |

```sql
CREATE FUNCTION impedir_alteracao() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Registro imutável em %: crie uma versão nova',
        TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER planos_imutaveis
    BEFORE UPDATE OR DELETE ON reserva.planos
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();
```

A função vai na migration V001; cada módulo cria o seu trigger na própria migration.

Para `calculadora.simulacoes`, onde `observacao` e `status` **podem** mudar, o trigger é seletivo:

```sql
CREATE FUNCTION simulacao_valores_imutaveis() RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.valor_bem, NEW.entrada, NEW.parcela, NEW.prazo,
        NEW.taxa_mensal, NEW.total_pago, NEW.total_juros)
       IS DISTINCT FROM
       (OLD.valor_bem, OLD.entrada, OLD.parcela, OLD.prazo,
        OLD.taxa_mensal, OLD.total_pago, OLD.total_juros)
    THEN
        RAISE EXCEPTION 'Valores de simulação são imutáveis. Registre nova simulação.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

#### Trigger de auditoria

Uma tabela de log alimentada por trigger em todas as tabelas de movimentação financeira. Vantagem sobre log de aplicação: sobrevive a bug no backend e a `UPDATE` manual rodado no console.

```sql
CREATE TABLE auditoria.log (
    id         BIGSERIAL PRIMARY KEY,
    tabela     TEXT NOT NULL,
    operacao   TEXT NOT NULL,
    registro   UUID,
    antes      JSONB,
    depois     JSONB,
    em         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

A auditoria fica só nas tabelas financeiras. Treino, saúde e agenda não precisam.

#### O que NÃO vai para trigger

Explicitamente, para não haver dúvida depois:

| Não implementar em trigger | Por quê |
|---|---|
| Motor de distribuição de aporte | Cascata de tetos e arredondamento por prioridade; precisa de teste de propriedade |
| Geração de parcelas | Divisão inexata com regra de resto; muda se a convenção mudar |
| Composição de fatura | Depende de calendário de feriados configurável por cartão |
| Cálculo de saldo | Já é `VIEW`; trigger criaria coluna que pode divergir |
| Qualquer juro | Bisseção em PL/pgSQL não é sério |
| Missões e consistência | Regras novas, que vão mudar bastante no começo |

O critério que separa: **trigger para o que nunca muda, backend para o que muda.** O motor de distribuição é a regra mais volátil do sistema — você reorganizou os percentuais sete vezes numa tarde. Regra volátil em trigger significa migration a cada ajuste, sem teste automatizado decente e sem stack trace quando quebra.

---

## Stack

| Camada | Escolha |
|---|---|
| Linguagem | Java 25 (LTS) |
| Framework | Spring Boot 3.5+ |
| Módulos | Spring Modulith (obrigatório, ver abaixo) |
| Banco | PostgreSQL 18 |
| Migrations | Flyway |
| Acesso a dados | Spring Data JDBC |
| Build | Gradle (Kotlin DSL) |
| Testes | JUnit 5 + AssertJ + Testcontainers |
| Front | Kotlin (Android) |
| Proxy HTTPS | Caddy (certificado automático) |
| IA | API do Claude, via módulo `integracao` |

**Spring Data JDBC em vez de JPA.** O domínio é composto de agregados pequenos e bem delimitados, com muita leitura e pouca navegação de grafo. JDBC dá controle direto do SQL, sem lazy loading, sem cache de primeiro nível, sem `@Transactional` mascarando N+1. Para consultas de projeção e composição de fatura você vai escrever SQL de qualquer jeito — melhor escrever explicitamente.

**Verifique o baseline de Java antes de fixar a versão do Spring Boot.** A compatibilidade com Java 25 varia entre linhas do Spring Boot, e a informação muda rápido. Confirme na documentação oficial antes de cravar no `build.gradle.kts`. O mesmo vale para a versão do Spring Modulith compatível.

### Java 25 no domínio

Use `record` para value objects e comandos, `sealed interface` + pattern matching para resultados de operações que podem falhar de várias formas. Isso substitui bem a exceção como fluxo de controle:

```java
sealed interface ResultadoDistribuicao {
    record Sucesso(Distribuicao distribuicao) implements ResultadoDistribuicao {}
    record TetoAtingido(Distribuicao distribuicao, List<CategoriaId> concluidas)
        implements ResultadoDistribuicao {}
    record PlanoInvalido(String motivo) implements ResultadoDistribuicao {}
}
```

O compilador exige que o switch trate todos os casos. Quando você adicionar um quarto resultado daqui a um ano, ele aponta cada lugar que precisa mudar.

**Virtual threads** ligados (`spring.threads.virtual.enabled=true`). Não muda nada perceptível numa app de um usuário, mas é grátis e remove a preocupação com pool de threads.

---

## Princípio estrutural: monólito modular

Um deploy, um banco, um processo. Microserviço aqui seria custo puro — você é um usuário, os dados são pequenos e as transações cruzam domínios.

Mas a separação interna é levada a sério, porque os módulos têm ritmos diferentes de mudança. Cartão de crédito tem regra complexa e estável; reserva tem regra simples e volátil (você já reorganizou os percentuais sete vezes). Manter fronteira permite mexer numa sem tocar na outra.

**Pacote por módulo, não por camada:**

```
br.dev.assistente
├── comum/              # kernel: Dinheiro, Competencia, Percentual, Semana
├── auth/
├── calculadora/        # core/ + historico/
├── contas/
├── fluxo/
│   ├── domain/
│   ├── application/
│   ├── infra/
│   └── api/
├── cartoes/
├── reserva/
├── treino/
├── saude/
├── sistema/
├── agenda/
└── integracao/         # claude/, google/, healthconnect/
```

O nome `br.dev.reserva` da primeira versão ficou pequeno quando o projeto passou a cobrir treino, saúde e agenda. Se a fase 1 já começou com o nome antigo, renomear é um refactor de IDE.

Cada módulo expõe uma fachada pública; o resto é package-private. Uma classe de `cartoes.domain` não deve ser importável de `reserva`.

### Spring Modulith: obrigatório

Na primeira versão ele era "considere". Agora é obrigatório, por dois motivos:

- **Fronteiras verificadas no build.** O teste quebra quando um módulo importa o interno de outro. Sem isso, a fronteira é só disciplina — e disciplina cede às 23h de uma sexta.
- **Eventos entre módulos.** O módulo `sistema` depende de eventos vindos de todos os outros. Com `@ApplicationModuleListener`, o listener roda depois do commit de quem publicou, numa transação própria.

**Eventos persistidos** (Event Publication Registry, starter JDBC, compatível com Spring Data JDBC): o evento fica gravado numa tabela até o listener terminar. Se o listener falhar ou o servidor cair no meio, o evento é reprocessado no reinício, e nenhuma missão fica errada por evento perdido.

Regras para os eventos:

- O evento é um `record` no pacote público do módulo que publica.
- Carrega IDs e o mínimo de dados (valor, data), nunca a entidade inteira.
- Todo listener é idempotente: o mesmo evento chegando duas vezes não duplica nada.

| Módulo | Evento | Quando | Quem escuta |
|---|---|---|---|
| `fluxo` | `GastoLancado` | Gasto registrado, à vista ou no crédito | `sistema` |
| `fluxo` | `GanhoRegistrado` | Ganho registrado | `sistema` (revisão) |
| `cartoes` | `FaturaPaga` | Fatura marcada como paga | `sistema`, `agenda` |
| `reserva` | `AporteFeito` | Aporte registrado e distribuído | `sistema` |
| `contas` | `ConciliacaoFeita` | Extrato conciliado | `sistema` (evolução: missão mensal de conciliar) |
| `treino` | `SessaoRealizada`, `MarcoAtingido`, `AusenciaRegistrada` | Ver documento 07 | `sistema` |
| `saude` | `MedicaoRegistrada`, `CheckupRegistrado` | Ver documento 08 | `sistema`, `treino` |
| `agenda` | `EventoAgendado`, `EventoConcluido` | Ver documento 11 | `sistema` |

A calculadora não publica nada: simulação não é movimento financeiro.

### Dependências entre módulos

```
                 auth  (transversal, ninguém depende)
                 comum (kernel: Dinheiro, Competencia, Percentual, Semana)
                   ▲
                contas  (fundacional: onde o dinheiro está)
                   ▲
      ┌────────────┼────────────┐
      │            │            │
    fluxo ──►  cartoes      reserva          calculadora
                                             (só comum)

    treino ──► saude   (check-up libera a Fase 2 do treino)

    agenda ──► cartoes, fluxo    (consultas públicas de vencimentos)
    agenda ──► fluxo             (captura rápida, só após confirmação)
    agenda ──► [CalendarioExterno]   (interface; integracao.google implementa)

    sistema ──► todos            (eventos + consultas públicas)
    ninguém ──► sistema

    integracao.healthconnect ──► treino, saude   (dados brutos, fase 16)
```

Regras:

1. **`comum` não depende de ninguém.** É só tipo de valor, sem I/O, sem Spring.
2. **`calculadora` não depende de módulo de negócio.** Divide-se em duas partes: `core`, função pura sem I/O, espelhada no cliente e extraível como biblioteca; e `historico`, que persiste simulações salvas. Só a segunda toca o banco.
3. **`contas` é fundacional e não conhece os módulos de negócio.** Ganho, gasto, fatura e aporte todos movimentam conta; a conta não sabe o que são.
4. **`cartoes` depende de `fluxo`** — um gasto no crédito nasce em `fluxo` e vira parcela em `cartoes`.
5. **`reserva` depende só de `contas`.** Ela recebe aportes e precisa saber em que conta eles caíram; não importa de onde o dinheiro veio.
6. **A ligação entre fluxo e reserva é do usuário, não do sistema.** O app mostra a sobra do mês e sugere o aporte. Quem aperta o botão é você.
7. **`sistema` só consome.** Lê por eventos e consultas públicas. Nenhum módulo depende dele, então dá pra desligar ou refazer o sistema sem tocar no resto.
8. **`agenda` lê as finanças só por consultas públicas** e nunca escreve nelas, com uma exceção: a captura rápida, que grava um gasto pela fachada do `fluxo` e só depois da sua confirmação.
9. **`integracao` não tem regra de negócio.** São adaptadores. Quem decide algo é o módulo de domínio, através de uma interface que ele mesmo define.
10. **`treino` consulta `saude`** só para saber se o check-up foi feito antes da semana 5.

O ponto 6 é decisão de produto, não de arquitetura. Aporte automático a partir da sobra parece elegante e é errado: sobra do mês não é o que você quer guardar necessariamente, e um lançamento automático que você não controla vira ruído que você aprende a ignorar. A missão "aporte do mês" do `sistema` é um lembrete; nada é aportado sozinho.

---

## Kernel compartilhado (`comum`)

Quatro tipos que atravessam o sistema inteiro.

### Dinheiro

```java
public record Dinheiro(BigDecimal valor) implements Comparable<Dinheiro> {

    private static final int ESCALA = 2;
    private static final RoundingMode MODO = RoundingMode.HALF_EVEN;

    public Dinheiro {
        Objects.requireNonNull(valor);
        valor = valor.setScale(ESCALA, MODO);
    }

    public static Dinheiro de(String v) { return new Dinheiro(new BigDecimal(v)); }
    public static final Dinheiro ZERO = de("0");

    public Dinheiro mais(Dinheiro o)  { return new Dinheiro(valor.add(o.valor)); }
    public Dinheiro menos(Dinheiro o) { return new Dinheiro(valor.subtract(o.valor)); }
    public boolean negativo()         { return valor.signum() < 0; }
}
```

`double` para dinheiro é bug garantido, não questão de estilo. `0.1 + 0.2 != 0.3` em ponto flutuante binário, e o erro se acumula sobre milhares de lançamentos.

`HALF_EVEN` (arredondamento bancário) em vez de `HALF_UP`: sobre muitas operações, `HALF_UP` enviesa sistematicamente para cima.

No Postgres: `NUMERIC(19,2)`. Nunca `float8`, nunca `money`.

### Competencia

O domínio financeiro raciocina em mês, não em dia. Aporte de setembro é aporte de setembro, tenha sido feito dia 2 ou dia 29.

```java
public record Competencia(YearMonth mes) implements Comparable<Competencia> {
    public Competencia proxima()        { return new Competencia(mes.plusMonths(1)); }
    public Competencia mais(int meses)  { return new Competencia(mes.plusMonths(meses)); }
    public Stream<Competencia> ate(Competencia fim) { /* ... */ }
}
```

Persistência: coluna `DATE` sempre com dia 1, mais `CHECK (EXTRACT(DAY FROM competencia) = 1)`. Ordena e compara nativamente, aceita `BETWEEN`, e o check impede que alguém grave um dia 15 por engano. Inteiro `yyyymm` também funcionaria, mas perde a aritmética de datas do banco.

### Percentual

```java
public record Percentual(int pontos) {
    public Percentual {
        if (pontos < 0 || pontos > 100)
            throw new IllegalArgumentException("Percentual fora de 0–100: " + pontos);
    }
    public BigDecimal fracao() {
        return BigDecimal.valueOf(pontos).divide(BigDecimal.valueOf(100));
    }
}
```

Inteiro, não decimal. Você nunca vai querer 45,5% — e permitir isso abre a porta para somas que não fecham em 100 por erro de arredondamento.

### Semana

Treino, consistência e revisão raciocinam em semana. Entra na fase 9.

```java
public record Semana(int ano, int numero) implements Comparable<Semana> {
    // ISO-8601: começa na segunda; semana 1 contém a primeira quinta do ano
    public LocalDate segunda() { /* ... */ }
    public Semana proxima()    { /* ... */ }
    public static Semana de(LocalDate data) { /* IsoFields */ }
}
```

Persistência: `DATE` da segunda-feira da semana, com `CHECK (EXTRACT(ISODOW FROM coluna) = 1)` — mesma ideia da competência com dia 1.

---

## Convenções transversais

### Identificadores

UUIDv7. Postgres 18 traz `uuidv7()` nativo:

```sql
id UUID PRIMARY KEY DEFAULT uuidv7()
```

UUIDv7 carrega timestamp no prefixo, então é ordenável por criação e não fragmenta índice como UUIDv4. Se por algum motivo a função não estiver disponível na sua instalação, gere na aplicação — mas prefira o banco.

**Exceção: tudo que pode nascer offline tem ID gerado no cliente.** O motivo é o pior caso de rede ruim: a requisição chega, o servidor grava, a resposta se perde, o cliente tenta de novo. Sem ID do cliente, isso vira registro duplicado. Com ele, `PUT /{id}` idempotente resolve — repetição retorna 200, não 409.

Hoje isso vale para: simulações salvas, gastos e ganhos, sessões de treino manuais, medições de saúde manuais e itens da captura rápida. Essas tabelas têm `id UUID PRIMARY KEY` **sem** `DEFAULT`, com comentário no DDL para ninguém "consertar" depois.

Cada agregado tem seu tipo de ID:

```java
public record CartaoId(UUID valor) {}
public record GastoId(UUID valor) {}
```

Verboso, e evita a categoria inteira de bug em que se passa um `UUID` de cartão onde se esperava um de gasto. O compilador pega.

### Erros

Formato único em toda a API:

```json
{
  "erro": "PLANO_NAO_SOMA_CEM",
  "mensagem": "Alocações somam 95%, precisam somar 100%",
  "detalhes": { "soma": 95, "faltando": 5 }
}
```

Código em `SCREAMING_SNAKE` para o front decidir comportamento; mensagem em português para exibir. `@RestControllerAdvice` centraliza a tradução.

**Em produção, nada de stack trace na resposta.** Com o backend exposto na internet, mensagem de erro detalhada é mapa para atacante. O detalhe vai para o log.

### Fuso horário e semana

`America/Sao_Paulo` fixo, na aplicação e no banco. Não é internacional e nunca vai ser. Cravar isso evita a classe de bug em que um lançamento feito às 22h vira do dia seguinte porque o servidor está em UTC — e servidor de VPS quase sempre vem em UTC.

```properties
spring.jackson.time-zone=America/Sao_Paulo
```

E no container: `TZ=America/Sao_Paulo`. Toda virada de dia e de semana (missões, consistência, jobs) é calculada nesse fuso.

**Semana começa na segunda**, no padrão ISO. Vale para consistência, revisão e treino. A semana 1 do plano de treino é a semana do `inicio` do plano, e o `inicio` precisa ser uma segunda (constraint no banco).

---

## Persistência

Um banco, um schema por módulo:

```
auth.usuarios          contas.contas            fluxo.ganhos
auth.dispositivos      contas.movimentacoes     fluxo.gastos
                       contas.conciliacoes      fluxo.categorias
calculadora.simulacoes                          fluxo.recorrentes

cartoes.cartoes        reserva.aportes          treino.planos
cartoes.parcelas       reserva.planos           treino.sessoes_planejadas
cartoes.faturas_pagas  reserva.distribuicoes    treino.sessoes_realizadas
cartoes.feriados       reserva.metas            saude.medicoes

sistema.missoes        agenda.pedidos           integracao.credenciais
sistema.revisoes       agenda.eventos           integracao.chamadas_ia
                       agenda.entrada           auditoria.log
```

Mais a tabela de publicação de eventos do Spring Modulith.

Schema separado documenta a fronteira no próprio banco e facilita ver quando uma consulta cruza módulos.

**Chave estrangeira entre schemas é permitida apenas na direção das dependências declaradas.** `cartoes.parcelas` pode referenciar `fluxo.gastos`. O contrário, não. `sistema` não tem FK para ninguém: guarda `origem_tipo` e `origem_id` como texto, justamente para não acoplar.

Migrations em Flyway, versionadas, nunca editadas depois de aplicadas. Uma migration por mudança, com nome descritivo: `V007__adiciona_dia_fechamento_cartao.sql`.

**Não use `ddl-auto`.** Nem `update`, nem `validate` como fonte de verdade. O schema é definido pelas migrations e só por elas.

---

## API

REST, JSON, prefixo `/api`. Substantivos no plural, verbos no método HTTP.

```
GET    /api/cartoes
POST   /api/cartoes
GET    /api/cartoes/{id}/fatura?competencia=2026-09
PUT    /api/gastos/{id}                        ← ID do cliente, idempotente
GET    /api/fluxo/horizonte?meses=12
POST   /api/reserva/aportes
GET    /api/reserva/aportes/previa?valor=1200.00
GET    /api/sistema/hoje
```

Três convenções importantes:

**Endpoints de prévia não persistem e não têm efeito colateral.** `GET /aportes/previa` e o equivalente de gasto parcelado são os mais chamados do sistema — o front dispara a cada tecla digitada. Devem ser baratos e idempotentes. Offline, o app registra sem prévia e mostra o efeito quando sincronizar.

**Competência sempre em `yyyy-MM`.** Não `2026-09-01`, não `set/2026`. Um formato só, em toda a API.

**Criação offline usa `PUT /{id}`.** Ver Identificadores.

---

## Cliente Kotlin

### Fila offline compartilhada

A fila local nasceu na calculadora, mas outras coisas também são criadas no celular, às vezes sem sinal: gastos, treino, medições, captura rápida e, na fase 16, os lotes do Health Connect. Por isso a fila é um componente compartilhado do app (`sync`) desde a fase 1:

1. Grava primeiro no armazenamento local, como pendente
2. A tela mostra o item na hora, marcado como pendente
3. Um worker envia quando há rede
4. Sucesso marca como sincronizado; falha mantém na fila
5. Sem limite de tentativas e sem descarte automático

Cada tipo de item só registra para qual endpoint vai. Construída genérica na fase 1, ela sai pelo mesmo custo de uma fila só da calculadora.

### Notificações locais

O app busca `/api/sistema/hoje` a cada sincronização e **agenda notificações locais** no próprio Android: missões da manhã, lembrete de pesagem, vencimento do dia. Funciona mesmo sem rede na hora da notificação e não depende de Firebase. Push pelo FCM continua possível no futuro, agora que o backend é público, mas não é necessário.

---

## Integrações externas (`integracao`)

A regra 1 diz que `comum` não tem I/O. As chamadas externas ficam num módulo próprio, atrás de interfaces definidas pelos módulos que as usam (portas e adaptadores):

| Adaptador | Implementa | Usado por | Fase |
|---|---|---|---|
| `integracao.claude` | `RedatorDeRevisao` | `sistema` | 12 |
| `integracao.claude` | `InterpretadorDePedidos` | `agenda` | 13 |
| `integracao.google` | `CalendarioExterno` | `agenda` | 13 |
| `integracao.healthconnect` | Endpoint de lote (documento 09) | `treino`, `saude` | 16 |

Por que interfaces: nos testes, `agenda` e `sistema` rodam com uma implementação falsa, sem rede e sem custo.

Cuidados com a API do Claude:

- **Prompts versionados** em arquivo no repositório, nunca montados em string espalhada pelo código.
- **Timeout e falha graciosa:** se a API não responder, a revisão sai sem o parágrafo e o pedido à secretária pede para você preencher os campos à mão.
- **Teto de gasto mensal:** cada chamada registra tokens e custo em `integracao.chamadas_ia`. Passou do teto configurado, as chamadas param até o mês seguinte.
- **Mínimo de dados:** a revisão envia só números agregados, nunca lançamentos individuais.

Credenciais das integrações: ver `01-modulo-auth.md`.

---

## Jobs agendados

O sistema não reage só a requisições. Algumas rotinas rodam sozinhas:

| Job | Quando | Módulo | Fase |
|---|---|---|---|
| Materializar feriados dos próximos anos | Anual | `cartoes` | 5 |
| Materializar recorrentes da competência | Dia 1 de cada mês | `fluxo` | 6 |
| Gerar missões do dia | Todo dia, 00:05 | `sistema` | 11 |
| Marcar missões perdidas | Todo dia, 00:00 (treino: segunda, 00:00) | `sistema` | 11 |
| Revisão da semana | Domingo, 20:00 (a confirmar) | `sistema` | 12 |
| Ler mudanças no Google Agenda | A cada 30 min | `agenda` | 13 |
| Vencimentos para a agenda | Todo dia, 06:00 | `agenda` | 14 |

Regras:

- **Todo job é idempotente.** Rodar duas vezes dá o mesmo resultado (chaves únicas no banco).
- **Recuperação no reinício.** Se o servidor ficou fora do ar na hora do job, ele roda ao subir.
- **Uma instância só.** Sem cluster, então não precisa de lock distribuído.

---

## Segurança

O backend fica **exposto na internet**, numa VPS. Isso muda a postura da primeira versão, que assumia uma aplicação privada.

A regra continua: **simples de verdade, não simples de mentira**. Mas "simples" agora inclui o mínimo que qualquer serviço público precisa:

- **Login por senha uma vez, token por dispositivo depois.** A senha não fica guardada no celular, e cada aparelho pode ser revogado. Detalhes em `01-modulo-auth.md`.
- **Limite de tentativas de login** por IP e global.
- **HTTPS obrigatório**, terminado no Caddy; a aplicação só aceita conexão vinda do proxy.
- **Postgres nunca exposto.** Só a aplicação fala com ele, pela rede interna do Docker.
- **Segredos só em variável de ambiente** (senha inicial, chave da API do Claude, chave de criptografia das credenciais). Nunca em `application.properties` versionado.

Não caia em: sessão em memória (perde no restart), senha em arquivo versionado (vaza no git), Actuator aberto, ou "vou colocar auth depois" (nunca coloca).

### Implantação

```
Internet ──443──▶ Caddy (TLS, Let's Encrypt) ──▶ app :8080 ──▶ postgres :5432
                                                  (rede interna Docker)
```

- **Firewall:** só 443 (e 80 para o desafio do certificado) abertos. SSH só com chave, de preferência em porta não padrão.
- **Docker Compose** com três serviços: `caddy`, `app`, `postgres`. Volume persistente para o banco.
- **`server.forward-headers-strategy=framework`** na aplicação, para o Spring reconhecer o `X-Forwarded-Proto` do Caddy e o `requiresSecure()` funcionar atrás do proxy.
- **Backup diário** com `pg_dump`, criptografado e enviado para fora da VPS. Backup que mora na mesma máquina não é backup. Testar a restauração uma vez por mês.
- **Atualizações de segurança** automáticas do sistema operacional.
- **Dados de saúde e financeiros** ficam no seu servidor: disco criptografado, se o provedor oferecer.

A escolha da VPS fica para depois. Requisito mínimo realista: 2 GB de RAM (JVM + Postgres), 20 GB de disco.

---

## Observabilidade

O mínimo que serve, sem virar projeto paralelo:

- **Actuator:** só `/actuator/health` público, e sem detalhes. `/metrics` e o resto atrás da autenticação.
- **Log estruturado** em JSON, com o ID da operação de negócio quando houver. Nunca logar senha, token ou corpo de requisição de login.
- **Um log de auditoria de domínio**: toda distribuição de aporte e toda geração de fatura registram o que foi calculado e com que plano. Quando você olhar um saldo daqui a um ano e achar estranho, vai querer refazer o caminho.
- **Log de tentativas de login falhas**, com IP. É o primeiro lugar a olhar se algo parecer errado.

Não instale Prometheus, Grafana ou tracing distribuído. Um usuário, um processo.

---

## Ordem de construção

| Fase | Módulo | Entrega | Por quê nessa ordem |
|---|---|---|---|
| 1 | `comum` + `calculadora` | Tipos de valor, cálculo de juros, Spring Modulith, fila offline genérica no app | Zero dependência. A calculadora sozinha já te serve na concessionária |
| 2 | `auth` | Login, token por dispositivo, limite de tentativas | Depois vira retrabalho em todos os controllers; e o backend é público |
| 3 | `contas` | Contas, movimentações, conciliação | Fundacional. Se vier depois de `fluxo`, exige migração de dados |
| 4 | `fluxo` | Ganhos, gastos à vista, categorias; eventos e `resumoDoMes` | Já nasce vinculado a conta |
| 5 | `cartoes` | Cartões, parcelas, faturas, dia útil; `FaturaPaga` e `proximosVencimentos` | Módulo mais complexo; precisa de fluxo e contas |
| 6 | `fluxo` parte 2 | Recorrentes, horizonte de 12 meses, `recorrentesPrevistos` | Depende de cartões para somar parcelas |
| 7 | `contas` parte 2 | Saldo projetado e alerta de negativo; `ConciliacaoFeita` | Precisa saber quais faturas e recorrentes debitam cada conta |
| 8 | `reserva` | Aportes, planos, distribuição, projeção; `AporteFeito` | Independente do resto, exceto conta de destino |
| 9 | `treino` (manual) | Plano de 24 semanas como dados, registro manual; tipo `Semana` | Plano pronto; funciona sem relógio |
| 10 | `saude` (manual) | Peso, cintura, check-up | Não precisa de relógio |
| 11 | `sistema`: missões + consistência | Missões do dia, mapa de consistência, notificações locais, jobs | Precisa de finanças e treino gerando eventos |
| 12 | `sistema`: revisão da semana | Revisão com `integracao.claude` | Precisa de algumas semanas de dados |
| 13 | `agenda`: secretária | Pedido em texto → horário livre → evento; `integracao.google` | Última área por decisão de prioridade |
| 14 | `agenda`: vencimentos | Faturas, recorrentes e lembretes de saúde na agenda | Liga cartões, fluxo e saúde à agenda |
| 15 | `agenda`: captura rápida | Caixa de entrada classificada pela IA | Porta de entrada da secretária |
| 16 | `integracao.healthconnect` | Dados do relógio automáticos | Adiado: ainda não há smartwatch |

A fase 1 é entregável em si. Não espere o sistema inteiro para ter a calculadora.

A fase 3 mudou de posição depois que `contas` entrou no desenho. Construir `fluxo` antes significaria ter gastos sem conta de origem e migrar tudo depois.

Uma versão mínima do `sistema` (só a lista de missões do dia, sem consistência) pode entrar logo depois da fase 9, se você quiser ver o painel cedo.

---

## Decisões registradas

| # | Decisão | Alternativa descartada | Motivo |
|---|---|---|---|
| 1 | Monólito modular | Microserviços | Um usuário, dados pequenos, transações cruzam domínios |
| 2 | Spring Data JDBC | JPA/Hibernate | Agregados pequenos, SQL explícito, sem lazy loading |
| 3 | `BigDecimal` escala 2 | `double`, centavos em `long` | Correção sem aritmética manual de escala |
| 4 | Planos imutáveis versionados | Plano editável | Permite responder "e se eu tivesse mantido o plano antigo" |
| 5 | Percentual inteiro | Decimal | Garante soma exata de 100 |
| 6 | Regra com estado no backend; matemática pura espelhada; cliente só coleta | Tudo no backend | Calculadora precisa funcionar offline; divergência travada por vetores de teste |
| 7 | Senha uma vez + token opaco por dispositivo | HTTP Basic em toda requisição; JWT | Backend público: senha fora do celular e token revogável por aparelho; JWT não traz nada para um usuário |
| 8 | Feriados em tabela, por escopo, com regra configurável por cartão | Biblioteca externa; conjunto único | Prática varia por emissor; municipais não são cobertos por biblioteca genérica |
| 9 | Aporte manual a partir da sobra | Automático | Sobra do mês não é necessariamente o que se quer guardar |
| 10 | Interface mira o vencimento nominal | Mirar a data adiada | Erro tardio custa rotativo; erro adiantado não custa nada |
| 11 | Saldo = última conciliação + movimentações | Soma acumulada pura; instantâneo puro | Lançamento esquecido corrompe a soma para sempre; conciliação dá ponto de correção |
| 12 | Transferência e pagamento de fatura como tipos próprios | Tratar como gasto | Evita contar a mesma compra duas vezes |
| 13 | Conta e categoria de reserva nunca se somam | Patrimônio único agregando os dois | São duas leituras do mesmo dinheiro |
| 14 | Trigger só para imutabilidade e auditoria | Regra de negócio em PL/pgSQL | Regra volátil em trigger exige migration a cada ajuste, sem teste de propriedade nem stack trace |
| 15 | Spring Modulith obrigatório, com eventos persistidos | Chamada direta entre módulos; eventos só em memória | O `sistema` não pode acoplar os outros; evento perdido vira missão errada |
| 16 | `sistema` só consome | Módulos consultam o sistema | Dá pra desligar sem quebrar nada |
| 17 | APIs externas em `integracao`, atrás de interfaces | Cliente HTTP dentro de `agenda` | Testável com implementação falsa; `comum` sem I/O |
| 18 | OAuth do Google pelo fluxo de servidor, com callback no backend | Autorização feita pelo app Android | Com o backend público, o fluxo padrão é o mais simples e documentado |
| 19 | ID no cliente para tudo que nasce offline | Só para simulações | Mesmo motivo da calculadora: resposta perdida duplica registro |
| 20 | Notificações locais | Push pelo FCM | Funcionam offline e sem dependência do Firebase |
| 21 | A IA só interpreta; o código decide | A IA escolhe o horário | Testável e sem evento criado em cima de outro |
| 22 | Health Connect na fase 16 | Logo depois do treino | Ainda não há smartwatch |
| 23 | Backend exposto na internet atrás de proxy HTTPS; banco nunca exposto | Rede privada com VPN | Acesso de qualquer lugar sem VPN no celular; integrações externas funcionam direto |
| 24 | Pacote raiz `br.dev.assistente` | `br.dev.reserva` | O projeto deixou de ser só a reserva |
