# REFACTOR-SPEC — Padronização de valores, IDs e modelos

**Projeto:** backend-azimute-core
**Data:** 2026-11-16
**Status:** revisão
**Objetivo:** refatorar, preservando o comportamento e o contrato da API

---

## 1. Estado Atual do Projeto

### Tecnologias

| Tecnologia | Versão | Papel |
|------------|--------|-------|
| Java | 25 | Runtime |
| Spring Boot | 4.1.2 | Framework HTTP |
| Spring Modulith | 2.1.1 | Fronteiras entre módulos (inclui ArchUnit) |
| Jackson | 3 (`tools.jackson`) | JSON |
| PostgreSQL | 18 | Banco (`uuidv7()` nativo) |
| JUnit 5, AssertJ, Mockito, jqwik | — | Testes unitários e de propriedade |
| Testcontainers | — | Testes de integração com Postgres real |
| JaCoCo | — | Gate de 90% de linhas e de ramos |

### Testes existentes

| Estado | Quantidade | Observação |
|--------|-----------|------------|
| Passando | 664 | `make test` na `main` em `3b20dfb` (PR #4) |
| Falhando | 0 | — |
| Cobertura | ≈ 98,9% linhas, ≈ 94,8% ramos | Gate em 90% |

### Como o código trata valores e modelos hoje

- **Tipos de valor em `comum`:** `Dinheiro`, `Competencia`, `Percentual` e `DiasUteis`, além das bases de erro. O módulo é OPEN, sem Spring e sem I/O, e o `ModularidadeTest` garante isso.
- **Entidades:** `record` imutáveis, com validação no construtor compacto (35 modelos). Não há builders. A criação passa por fábricas estáticas (`Dinheiro.de`, `Competencia.de`, `XxxResponse.de`).
- **IDs:** um record tipado por agregado, `XxxId(UUID valor)`, dentro do módulo dono.
- **JSON:** `Dinheiro` sai como string decimal e `Competencia` como `"yyyy-MM"`, pelo `JacksonKernelConfig` em `plataforma.web`. O módulo `plataforma` também é OPEN.

### Achados (motivo desta refatoração)

| # | Achado | Onde |
|---|--------|------|
| A1 | Três formatos para taxa e percentual: `TaxaMensal` como fração; `BigDecimal` cru com 8 casas no histórico; `BigDecimal` com 4 casas (fração no banco, pontos na API) na contrapartida. O `Percentual` de `comum` não é usado no código de produção: está reservado para as alocações da reserva (doc 00) | `calculadora/core/TaxaMensal`, `calculadora/historico/DadosSimulacao`, `fluxo/folha/RegraContrapartida`, `FolhaController` |
| A2 | A regra "dinheiro positivo com até 2 casas, sem arredondar" está espalhada: `@Digits` no PUT e checagem à mão no PATCH. O `Dinheiro` arredonda em silêncio (HALF_EVEN) | `GastosController`, `GanhosController`, `ContasController`, `FolhaController` |
| A3 | Conversões de competência repetidas: `mes().atDay(1)` e `YearMonth.from(rs...)` | 9 arquivos de repositório e tabela |
| A4 | Nove records de ID quase idênticos; `DispositivoId` não tem o `toString` dos outros | `*Id.java` |
| A5 | UUID cru onde deveria haver ID tipado. Recebimento e lançamento da folha não têm tipo de ID | `EfeitosRepository` (17), `EfeitosDoHolerite`, `AdiantamentoRepository`, `fluxo/tabelas` |
| A6 | Versão de UUID misturada no servidor: o banco gera v7; o Java gera v4 com `UUID.randomUUID()` | `HoleriteService:129`, `ContasFacadeImpl:73` |
| A7 | String no lugar de enum: papel do recebimento e do lançamento, tipo da movimentação, origem, natureza e forma | `fluxo/folha/*`, `fluxo/tabelas/*`, `contas/Movimentacao`, respostas |
| A8 | Leitores de campo do PATCH (texto, data, competência, valor, ID, enum) copiados em 6 controllers, já com divergência: o ID precisa vir como texto em `Gastos`, mas qualquer valor é aceito em `Ganhos` | `Contas`, `Historico`, `Gastos`, `Ganhos`, `Categorias`, `Folha` |
| A9 | `@Digits(integer = 17, fraction = 2)` repetido 19 vezes | Requests e controllers |
| A10 | `Instant` (20 usos) e `OffsetDateTime` (11 usos) misturados; conversão de `Timestamp` para `Instant` duplicada | `auth`, `calculadora/historico` |

### Uso de UUID (resposta registrada)

| Origem | Versão | Onde |
|--------|--------|------|
| Banco, `DEFAULT uuidv7()` | **v7** | Tabelas de `auth`, `contas`, `fluxo.categorias` e `fluxo.holerites` |
| Cliente, `PUT /{id}` (nasce offline) | A que o app gerar; o servidor aceita qualquer versão | `calculadora.simulacoes`, `fluxo.gastos`, `fluxo.ganhos`, movimentações |
| Java, `UUID.randomUUID()` | **v4** (contraria a doc 00) | Holerite novo e movimentação criada pela fachada de contas |
| Java, `UUID.nameUUIDFromBytes` | **v3**, determinístico de propósito (T6) | Recebimentos, lançamentos e gastos gerados pelo holerite |

---

## 2. Estratégia de testes

| Camada | Ferramenta | Papel nesta refatoração |
|--------|-----------|-------------------------|
| Unitário | JUnit, AssertJ, jqwik | Tipos novos de `comum` (`Taxa`, gerador de UUIDv7, leitor de campos), com todas as variações de formato de entrada |
| Integração | `@SpringBootTest`, MockMvc, Testcontainers | Caracterizar o JSON atual antes de mexer, e provar depois de cada fase que ele não mudou |
| Arquitetura | ArchUnit (já vem com o Modulith) | Travar o padrão para o futuro (Fase 6) |

O gate do JaCoCo (90%) continua valendo. Toda fase termina com o `make test` completo, rodado de verdade.

---

## 3. Mapa de Arquivos

```
src/main/java/br/dev/azimute/
├── comum/
│   ├── [NEW] Taxa.java                  — percentual decimal; fração por dentro; dePontos/deFracao
│   ├── [NEW] Identificador.java         — contrato de todo ID: UUID valor()
│   ├── [NEW] Uuid7.java                 — gerador de UUIDv7 em Java puro, para IDs criados no servidor
│   ├── [NEW] LeitorDeCampos.java        — leitura do corpo de PATCH com acúmulo de erros por campo
│   ├── [MOD] Dinheiro.java              — fábrica que recusa mais de 2 casas, sem arredondar
│   ├── [MOD] Competencia.java           — primeiroDia() e doDia(LocalDate)
│   └── [REF] Percentual.java            — continua inteiro, para as alocações da reserva
├── plataforma/web/
│   └── [NEW] ValorMonetario.java        — anotação composta: @Digits(17,2), positivo ou não negativo
├── calculadora/core/[MOD] TaxaMensal.java        — conversão para Taxa, sem mudar a API dela
├── calculadora/historico/[MOD] DadosSimulacao, ConferenciaDeSnapshot, SimulacaoRepository, HistoricoController
├── contas/[MOD] *Id, Movimentacao, NovaMovimentacao, ContasFacadeImpl, ContasController, Requests
├── auth/[MOD] DispositivoId, DispositivoRepository
├── fluxo/[MOD] *Id, Recebimento, Gastos/Ganhos/CategoriasController, repositórios
├── fluxo/tabelas/[MOD] Tabela*  [NEW] PapelDeRecebimento.java, RecebimentoId.java
└── fluxo/folha/[MOD] EfeitosRepository, EfeitosDoHolerite, AdiantamentoRepository, HoleriteService,
                       RegraContrapartida, FolhaController  [NEW] LancamentoDaFolhaId, PapelDeLancamento
src/test/java/br/dev/azimute/
├── comum/[NEW] TaxaTest, Uuid7Test, LeitorDeCamposTest
├── [NEW] ContratoJsonIT (ou ITs pontuais por módulo)   — caracterização da Fase 1
└── [NEW] PadroesDoCodigoTest                           — regras de arquitetura da Fase 6
assistente-docs/[MOD] 00-arquitetura.md                — seção de convenções
```

---

## 4. Fases de Implementação

Cada fase vira uma branch `refactor/padronizacao-fase-N`, com PR (não draft) e merge `--no-ff` na branch pai `refactor/padronizacao`. No fim, um PR da pai para a `main`.

### Fase 1 — Base de segurança (caracterização)

**Objetivo:** fixar em teste o comportamento atual que as fases 2 a 5 vão tocar, antes de qualquer mudança.

**Testes:**
- [ ] Contrapartida → o `GET` e o `POST` de regras devolvem `percentual` como string em pontos (`"50.00"`), e o banco guarda a fração (`0.5000`).
- [ ] Simulação → `taxaMensal` e `taxaAnual` saem no JSON com a mesma forma e escala de hoje.
- [ ] Respostas com enum → `papel`, `tipo`, `origem`, `natureza` e `forma` saem com os mesmos textos de hoje.
- [ ] PATCH → para cada campo de cada um dos 6 controllers, um valor inválido gera a mesma mensagem em `detalhes.campos`.
- [ ] Valor com 3 casas → 422 no PUT e no PATCH. Nunca é arredondado.
- [ ] Antes de escrever um teste novo, conferir se a cobertura já existe nos ITs atuais e reutilizar.

**Checklist:** 664+ testes verdes; nenhuma mudança em `src/main`.

### Fase 2 — Valores em `comum`

**Objetivo:** uma forma única para taxa, dinheiro exato e competência no banco (A1, A2, A3).

**O que fazer:**
- **`Taxa`:** guarda a fração em `BigDecimal`, sem arredondar. Nasce só por `Taxa.dePontos(...)` ou `Taxa.deFracao(...)`; expõe `fracao()` e `pontos()`; não aceita valor negativo.
- **Quem passa a usar `Taxa`:** `RegraContrapartida`, e no histórico `taxaMensal` e `taxaAnual`. A escala fica a cargo de quem grava (8 casas no histórico, 4 na contrapartida), como hoje.
- **`TaxaMensal`:** ganha conversão de e para `Taxa` e mantém a API atual, porque o core é espelhado no app (regra 2 da doc 00).
- **`Dinheiro`:** fábrica que recusa mais de 2 casas (lança erro, não arredonda), para os leitores de corpo. O construtor continua igual.
- **`Competencia`:** `primeiroDia()` e `doDia(LocalDate)` substituem `mes().atDay(1)` e `YearMonth.from(...)` em todos os repositórios.
- **`Percentual`:** não muda.

**Testes:**
- [ ] `Taxa`: pontos e fração em todos os formatos (`"3.51"`, `"3,51"` recusado, `"0"`, negativo, mais de 8 casas, nulo); propriedade jqwik de que a ida e volta entre pontos e fração não perde valor.
- [ ] Dinheiro exato: `"10"`, `"10.5"`, `"10.50"` aceitos; `"10.505"`, `"abc"`, `" "` recusados.
- [ ] `Competencia`: ida e volta com `LocalDate`.

**Checklist:** JSON da Fase 1 inalterado; a fração da contrapartida no banco é igual à de antes.

### Fase 3 — IDs

**Objetivo:** um contrato único de ID, IDs tipados no lugar de UUID cru e geração v7 no servidor (A4, A5, A6).

**O que fazer:**
- **`Identificador`:** interface em `comum` com `UUID valor()`. Os 9 `*Id` a implementam; `toString` devolve o UUID em todos, inclusive no `DispositivoId`.
- **IDs novos:**
  - `RecebimentoId`, em `fluxo.tabelas`;
  - `LancamentoDaFolhaId`, em `fluxo.folha`.
- **Onde trocar UUID cru:** `EfeitosRepository`, `EfeitosDoHolerite`, `AdiantamentoRepository` e as linhas de `fluxo.tabelas`.
- **`Uuid7.novo()`:** fica em `comum` e substitui o `UUID.randomUUID()` do holerite e da fachada de contas.
- **O que fica como está:**
  - o `DEFAULT uuidv7()` do banco;
  - o v3 determinístico da folha (T6), que é intencional;
  - o ID que vem do cliente, que continua aceito em qualquer versão;
  - a `ContasFacade`, que continua com `UUID referencia`. Exceção documentada: `contas` não conhece os IDs de quem chama.

**Testes:**
- [ ] `Uuid7`: a versão é 7 e a variante é IETF; IDs gerados em sequência são crescentes; não há colisão em 100 mil gerações.
- [ ] O holerite e a movimentação criada pela fachada nascem com UUID v7 (IT que lê a versão do ID gravado).
- [ ] As referências v3 da folha continuam idênticas, para a reaplicação não duplicar nada.

**Checklist:** nenhum `UUID.randomUUID()` sobra em código de produção fora do `DispositivoService` (que usa para o hash fictício, sem ser ID).

### Fase 4 — Enums no lugar de String

**Objetivo:** valores enumeráveis com tipo (A7).

**O que fazer:**
- **Enums novos:**
  - `PapelDeRecebimento`, em `fluxo.tabelas`, substitui o `Recebimento.Papel` e as Strings da folha;
  - `PapelDeLancamento`, em `fluxo.folha`;
  - `TipoMovimentacao`, em `contas`, no lugar do `tipo()` que devolve String.
- **Respostas:** passam a usar os enums que já existem (`Holerite.Origem`, `NaturezaRubrica`, `TipoGanho`) e a forma do gasto.
- **JSON:** continua igual, porque o Jackson serializa o enum pelo `name()`, que é o texto de hoje.
- **Requests:** continuam recebendo texto, com a mensagem de erro de hoje, e convertem para o enum no mapeamento.

**Testes:** os da Fase 1 cobrem o contrato; mais um teste unitário por enum novo, verificando que os nomes batem com o CHECK do banco.

### Fase 5 — Leitura de corpo e validação

**Objetivo:** um único leitor de campos e uma única anotação de valor monetário (A8, A9, A10).

**O que fazer:**
- **`LeitorDeCampos`:** fica em `comum`, em Java puro. Lê texto não vazio, data, competência, dinheiro exato positivo, ID (com a fábrica do tipo) e enum (com a mensagem do módulo), acumulando os erros por campo. No fim, quem lança o erro é o módulo, com a sua `ErroDeDadosInvalidos`. Substitui os leitores dos 6 controllers.
- **Mensagens:** as de hoje, campo a campo, como fixado na Fase 1.
- **Divergência do ID:** passa a exigir texto em todos os controllers. Não muda nada na prática, porque número JSON nunca forma um UUID válido.
- **`@ValorMonetario`:** anotação composta em `plataforma.web` (módulo OPEN), com variantes positivo e não negativo. Substitui as 19 ocorrências de `@Digits(integer = 17, fraction = 2)`.
- [PENDENTE: A10 — confirmar se `Instant` no domínio e `OffsetDateTime` na API é uma convenção ou uma mistura. Se for convenção, documentar e centralizar a conversão de `Timestamp`; se não for, definir a regra antes de mexer.]

**Testes:**
- [ ] `LeitorDeCampos`: cada tipo com campo ausente (não muda), nulo, formato errado e valor no limite.
- [ ] Os ITs de PATCH da Fase 1 continuam verdes sem nenhuma alteração.

### Fase 6 — Regras como teste e documentação

**Objetivo:** o padrão se mantém sozinho; quem fugir dele recebe um teste falhando.

**Regras (ArchUnit, em `PadroesDoCodigoTest`):**
- [ ] Todo record `*Id` implementa `Identificador`.
- [ ] Nenhuma classe de produção chama `UUID.randomUUID()`, exceto o `DispositivoService` (hash fictício).
- [ ] Nenhuma classe se chama `*Builder`, e nenhum record tem setter.
- [ ] `BigDecimal` não aparece em record de domínio, fora de uma lista explícita: Requests de entrada, o core de juros e o `RubricaLida.referencia`.
- [ ] Controllers não declaram `Set<String>` de campos editáveis (usam `CamposEditaveis`).

**Documentação:** seção "Convenções de valores e modelos" no `00-arquitetura.md`, cobrindo:
- quando usar `Taxa` e quando usar `Percentual`;
- `Identificador` e UUIDv7 gerado no servidor;
- records validados no construtor, fábricas `de(...)` e ausência de builders;
- `LeitorDeCampos` e `@ValorMonetario`;
- bases de erro e `CamposEditaveis`;
- a exceção da `ContasFacade`.

---

## 5. Ordem e dependências

```
Fase 1 → Fase 2 → Fase 3 → Fase 4 → Fase 5 → Fase 6
```

- **Fase 1:** bloqueante. Sem caracterização, nada avança.
- **Fases 2, 3 e 4:** mexem em arquivos em comum (repositórios do fluxo e da folha) e por isso ficam em sequência.
- **Fase 5:** depende do dinheiro exato da Fase 2.
- **Fase 6:** depende de todas, porque as regras só passam com o padrão aplicado.

## 6. Riscos

| Risco | Fase | Mitigação |
|-------|------|-----------|
| Mudar a escala da taxa no banco ou no JSON | 2 | A escala continua a cargo de quem grava; os ITs da Fase 1 fixam a forma do JSON |
| Quebrar a API do `TaxaMensal`, espelhada no app | 2 | `TaxaMensal` só ganha conversões; o `ModularidadeTest` do core continua valendo |
| Mudar a referência v3 da folha e duplicar efeitos na reaplicação | 3 | Teste explícito de que as referências continuam idênticas |
| Gerador v7 caseiro com bug de ordenação ou colisão | 3 | Testes de versão, variante, monotonicidade e colisão; implementação curta, seguindo a RFC 9562 |
| Mensagens de erro mudarem sem querer | 5 | Os ITs da Fase 1 comparam a mensagem campo a campo |
| Regra de arquitetura rígida demais travar feature legítima | 6 | Exceções em lista explícita, com o motivo no próprio teste |

## 7. Decisões registradas

| Decisão | Escolha | Motivo |
|---------|---------|--------|
| Percentual | Opção A: `Percentual` inteiro (reserva) + `Taxa` decimal nova | A soma exata em 100 das alocações exige inteiro (doc 00); taxas exigem decimal |
| IDs de entidade | Ficam no módulo dono; só o contrato vai para `comum` | `comum` não pode conhecer o domínio dos módulos |
| Builders | Não adotar | Records validados no construtor e fábricas nomeadas; builder permite objeto pela metade |
| UUID gerado no servidor | v7 (banco ou `Uuid7`) | Ordenável por criação, não fragmenta índice (doc 00) |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|--------|------|-----------|
| 1.0 | 2026-11-16 | Criação inicial, com a opção A para percentual e o levantamento de UUID |
