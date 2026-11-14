# SPEC — Azimute Core: Módulo `contas`, parte 1 (Fase 3 do roadmap)

**PRD de referência:** `.planning/prd_contas.md` (v1.8, aprovado)
**Data:** 2026-11-14
**Status:** aprovado
**Autor:** Claude Code

> Nomenclatura: a "Fase 3" do título é a do roadmap geral (`00-arquitetura.md`). As **Fases 1 a 5** deste documento são as etapas de implementação deste SPEC.

---

## 1. Visão Geral Técnica

O módulo `contas` entra no monólito modular existente (Spring Boot 4.1, Spring Modulith 2.1), no pacote `br.dev.azimute.contas`, que já existe vazio e já é verificado pelo `ModularidadeTest`.

**Mesmos padrões das fases anteriores:**
- **Persistência:** `JdbcClient` com SQL explícito, como em `SimulacaoRepository` e `DispositivoRepository`. A conversão `Dinheiro` ↔ `NUMERIC(19,2)` acontece no repositório. O schema vem só do Flyway (`V004__contas.sql`).
- **Erros:** subclasses de `ErroDeNegocio`. O `TradutorDeErros` já mapeia `VALIDACAO` → 422, `NAO_ENCONTRADO` → 404 e `CONFLITO` → 409.
- **Dinheiro na API:** string decimal (`"2300.00"`), pelo módulo Jackson do kernel (decisão T2 do SPEC anterior).
- **Segurança:** os endpoints ficam sob `/api/**`, que o `SecurityConfig` já protege por token (SEC01). Não há mudança na segurança.
- **Idempotência:** `PUT` com ID do cliente e `ON CONFLICT DO NOTHING`, como o histórico da calculadora (P1).

**Fronteira do módulo.** Ficam públicos, no pacote base, só os tipos que os outros módulos vão usar: `ContaId`, `Conta`, `TipoConta`, `Funcao`, `ContasFacade` e `FontesDeDivida`. O resto (serviços, repositórios, controller, DTOs, exceções) é package-private. O `ModularidadeTest` continua sendo a verificação (RF31).

**Cálculo do saldo (RN06, P2).** Existe uma única função de saldo, usada pela conta, pelo extrato, pela conciliação e pelo consolidado:

> `saldoEm(conta, D)` = `saldoReal` da última conciliação com `data ≤ D` + Σ créditos − Σ débitos das movimentações com `conciliação.data < data ≤ D`.
>
> `saldoAtual(conta)` = `saldoEm(conta, +∞)`: a última conciliação mais todas as movimentações com data posterior a ela.

Toda conta tem ao menos uma conciliação, criada junto com ela (RF01), então a base sempre existe. Movimentação com data igual ou anterior à última conciliação fica no extrato, mas não altera o saldo atual (P2).

**Movimentação como tipo selado (RNF02).** `Movimentacao` é uma `sealed interface` com cinco `record`s. A tabela é uma só (`contas.movimentacoes`), com a constraint `direcao_coerente` (RN04). O sentido de cada movimentação em relação a uma conta (crédito ou débito) é decidido num `switch` exaustivo, sem `default`, para que um tipo novo quebre a compilação.

**Datas de controle (RN14).** Padrão do projeto a partir deste módulo:

| Tabela | `created_at` | `updated_at` | `deleted_at` | Motivo |
|---|---|---|---|---|
| `contas.contas` | sim | sim | não | A conta muda (apelido, função, limite, ativa), mas não é excluída: desativar é o caminho, e a conciliação inicial não pode sumir |
| `contas.movimentacoes` | sim | sim | sim | Exclusão lógica e restauração (RF13, RF15); `updated_at` registra a última exclusão ou restauração |
| `contas.conciliacoes` | sim | não | não | Imutável por trigger (RN07): nunca é alterada nem excluída |

Todas as consultas de movimentação filtram `deleted_at IS NULL`. As três colunas são `TIMESTAMPTZ`.

**Sem auditoria (decisão do PRD v1.0).** Não há schema `auditoria`. A única proteção no banco, além das constraints, é o trigger de imutabilidade de `contas.conciliacoes`, que reaproveita a função `impedir_alteracao()` da V001 (RN07).

### Decisões técnicas deste SPEC

| # | Decisão | Motivo |
|---|---|---|
| T1 | A fachada referencia ganho, gasto e cartão por `UUID`, não por `GanhoId`, `GastoId` ou `CartaoId` | Esses tipos pertencem a `fluxo` e `cartoes`, que ainda não existem. Criá-los em `contas` inverteria a dependência. Na fase 4 do roadmap, o `fluxo` passa `ganhoId.valor()` |
| T2 | `DELETE` e `restaurar` só valem para `TRANSFERENCIA` e `RENDIMENTO`. As outras → 422 `MOVIMENTACAO_DE_OUTRO_MODULO` | Entrada, saída e pagamento de fatura nascem em outro módulo. Excluir por aqui deixaria o gasto em `fluxo` sem a saída correspondente |
| T3 | Excluir movimentação que não existe ou já excluída → 204. Restaurar movimentação que não existe → 404; que não está excluída → 200 sem mudança | Mesmo comportamento da exclusão de simulação (decisão T3 do SPEC anterior): o reenvio pela fila não vira erro |
| T8 | `updated_at` é preenchido pelo SQL da aplicação (`SET updated_at = now()`), não por trigger | Decisão 14 da doc 00: trigger só para imutabilidade e auditoria |
| T9 | `PUT` com o ID de uma movimentação excluída → 409 `MOVIMENTACAO_EXCLUIDA` | O reenvio não pode ressuscitar o que foi excluído; para voltar, existe o `restaurar` |
| T10 | Saldo numa data anterior à primeira conciliação da conta é zero | A conta ainda não tinha marco; aparece no `saldoInicial` de um extrato que começa no dia da criação |
| T4 | Reenvio de conciliação idêntica (mesma conta, mesma data, mesmo `saldoReal`) → 200 com a existente; mesma data com outro `saldoReal` → 422 `CONCILIACAO_FORA_DE_ORDEM` (P3) | Conciliação feita sem sinal também passa pela fila offline |
| T5 | A fachada não deixa duas movimentações para o mesmo `gasto_id`, `ganho_id` ou (`cartao_id`, `fatura_comp`). Índices únicos parciais no banco; o reenvio devolve a existente | É o equivalente da idempotência para chamadas entre módulos, e impede contagem dupla |
| T6 | Apelido único ignorando maiúsculas e espaços nas pontas: índice único em `lower(btrim(apelido))` | RN09, garantida no banco e não só na aplicação |
| T7 | O limite de cheque especial fica numa coluna com `CHECK (limite >= 0 AND (tipo = 'CORRENTE' OR limite = 0))` | Limite fora de conta corrente é rejeitado também no banco |

## 2. Tecnologias e Versões

Nenhuma tecnologia nova. As mesmas das fases anteriores:

| Tecnologia | Versão | Uso nesta alteração |
|---|---|---|
| Java | 25 | `sealed interface` e `switch` exaustivo nas movimentações |
| Spring Boot | 4.1.2 | Web MVC, JdbcClient, Validation, Flyway |
| Spring Modulith | 2.1.1 | Verificação da fronteira de `contas` |
| PostgreSQL | 18 | Schema `contas`, `uuidv7()`, constraints, trigger |
| Testcontainers, JUnit 5, AssertJ, Mockito | gerenciados pelo Boot | Testes de integração e unitários |
| JaCoCo | plugin do Gradle | Gate de 90% de linhas e de ramos (RNF05) |

## 3. Mapa de Arquivos

```
backend-azimute-core/
├── [REF] .planning/prd_contas.md
├── [REF] assistente-docs/06-modulo-contas.md
├── [MOD] .planning/spec_contas.md                         — checklists marcados a cada fase
├── [MOD] Makefile                                         — alvos de conta, transferência e conciliação para teste manual
├── src/main/resources/db/migration/
│   ├── [REF] V001__fundacao.sql                           — função impedir_alteracao()
│   ├── [NEW] V004__contas.sql                             — schema contas, 3 tabelas, constraints, índices, trigger
│   └── [NEW] V005__padrao_datas.sql                       — created_at/updated_at/deleted_at nas tabelas das fases 1–2 (Fase 6)
├── [MOD] src/main/java/br/dev/azimute/calculadora/historico/… — exclusão lógica e restauração de simulação (Fase 6)
├── [MOD] src/main/java/br/dev/azimute/auth/*Repository.java   — SQL com as colunas renomeadas (Fase 6)
├── src/main/java/br/dev/azimute/contas/
│   ├── [MOD] package-info.java                            — descrição do módulo
│   ├── [NEW] ContaId.java                                 — público: identificador da conta
│   ├── [NEW] Conta.java                                   — público: record da conta
│   ├── [NEW] TipoConta.java, Funcao.java                  — públicos: enums
│   ├── [NEW] ContasFacade.java                            — público: API para fluxo, cartoes e reserva
│   ├── [NEW] FontesDeDivida.java                          — público: interface que cartoes implementa (RF32)
│   ├── [NEW] Movimentacao.java                            — sealed interface + 5 records
│   ├── [NEW] MovimentacaoId.java, ConciliacaoId.java
│   ├── [NEW] Conciliacao.java                             — record da conciliação
│   ├── [NEW] Saldo.java                                   — saldo atual + cheque especial (RN11, P8)
│   ├── [NEW] CalculoDeSaldo.java                          — função pura: base + movimentações → saldo; sentido por conta
│   ├── [NEW] ContaRepository.java                         — SQL de contas
│   ├── [NEW] MovimentacaoRepository.java                  — SQL de movimentações
│   ├── [NEW] ConciliacaoRepository.java                   — SQL de conciliações
│   ├── [NEW] ContaService.java                            — criar, listar, alterar, saldo
│   ├── [NEW] MovimentacaoService.java                     — transferência, rendimento, exclusão, extrato
│   ├── [NEW] ConciliacaoService.java                      — conciliar, histórico
│   ├── [NEW] ConsolidadoService.java                      — posição geral e saldo em conta
│   ├── [NEW] ContasFacadeImpl.java                        — implementação da fachada
│   ├── [NEW] ContasController.java                        — endpoints /api/contas/**
│   ├── [NEW] *Request.java / *Response.java               — DTOs da API
│   └── [NEW] *Exception.java                              — erros de negócio do módulo (seção 6.4)
└── src/test/java/br/dev/azimute/contas/
    ├── [NEW] MontadorDeConta.java                         — dados de teste
    ├── [NEW] SchemaContasIT.java                          — constraints e trigger (Fase 1)
    ├── [NEW] MovimentacaoTest.java                        — sentido por conta, switch exaustivo (Fase 1)
    ├── [NEW] CalculoDeSaldoTest.java                      — saldo, cheque especial, acima do limite (Fase 2)
    ├── [NEW] ContaServiceTest.java                        — regras de criação e alteração (Fase 2)
    ├── [NEW] ContasIT.java                                — API de contas e saldo (Fase 2)
    ├── [NEW] MovimentacaoServiceTest.java                 — idempotência, exclusão, extrato (Fase 3)
    ├── [NEW] MovimentacoesIT.java                         — API de movimentações e extrato (Fase 3)
    ├── [NEW] ConciliacaoServiceTest.java                  — divergência, ordem, reenvio (Fase 4)
    ├── [NEW] ConciliacoesIT.java                          — API de conciliação, imutabilidade (Fase 4)
    ├── [NEW] ConsolidadoServiceTest.java                  — posição geral, saldo em conta, dívidas (Fase 5)
    ├── [NEW] ContasFacadeIT.java                          — fachada e unicidade por referência (Fase 5)
    └── [NEW] ConsolidadoIT.java                           — API do consolidado (Fase 5)
```

---

## 4. Fases de Implementação

**Git:** branch pai `feature/contas`, criada a partir de `feature/fundacao-calculadora-auth` (a `main` ainda só tem documentação). Uma branch filha por fase: `feature/contas-fase-N`, com merge `--no-ff` na pai ao fim de cada uma. O PR draft depende de existir um remoto.

---

### Fase 1 — Banco e modelo

**Objetivo:** o schema `contas` existe e o banco recusa dado incoerente; o modelo de domínio compila com o `switch` exaustivo.

**Arquivos desta fase:** `V004__contas.sql`; `package-info.java`; `ContaId`, `Conta`, `TipoConta`, `Funcao`, `Movimentacao`, `MovimentacaoId`, `Conciliacao`, `ConciliacaoId`; `SchemaContasIT`, `MovimentacaoTest`, `MontadorDeConta`.

**O que fazer:**
- `V004`: schema `contas` com as tabelas da seção 6.1 (RNF01).
  - `contas.contas` com o índice único de apelido (T6) e o `CHECK` do limite (T7).
  - `contas.movimentacoes`: ID com `DEFAULT uuidv7()` (a fachada não envia ID; a API envia), `valor > 0` (RN05), `direcao_coerente` (RN04), índices por origem e destino, e índices únicos parciais por referência (T5).
  - `contas.conciliacoes`: `UNIQUE (conta_id, data)` e o trigger `conciliacoes_imutaveis` com `impedir_alteracao()` (RN07).
- Records e enums do modelo (RF02, RF14, RNF02). `Movimentacao` expõe, para uma conta, se é crédito, débito ou não a envolve. Transferência é débito na origem e crédito no destino (RN02).

**Testes (TDD):**
- [x] Banco → transferência com origem igual ao destino → violação de `direcao_coerente`
- [x] Banco → `SAIDA` com conta de destino → violação de `direcao_coerente`
- [x] Banco → `ENTRADA` sem destino → violação
- [x] Banco → `valor = 0` e `valor < 0` → violação
- [x] Banco → `UPDATE` e `DELETE` em `contas.conciliacoes` → exceção do trigger
- [x] Banco → dois apelidos `"Nubank"` e `" nubank "` → violação do índice único
- [x] Banco → limite de cheque especial em conta `POUPANCA` → violação; em `CORRENTE` → aceito
- [x] Banco → duas saídas vivas com o mesmo `gasto_id` → violação do índice único parcial; com a primeira excluída (`deleted_at` preenchido) → aceita
- [x] Banco → `created_at` e `updated_at` preenchidos na inserção; `deleted_at` nulo (RN14)
- [x] Modelo → transferência é débito na origem, crédito no destino e não envolve uma terceira conta
- [x] Modelo → entrada e rendimento são crédito; saída e pagamento de fatura são débito
- [x] Modularidade → `ModularidadeTest` continua passando

**Checklist de validação:**
- [x] RF02, RF14, RN04, RN05, RN07 (parte do banco), RN09 (banco), RN14, RNF01, RNF02 atendidos
- [x] Testes das fases anteriores passando

**Critério de avanço:** checklist completo → Fase 2.

---

### Fase 2 — Contas e saldo

**Objetivo:** o usuário cadastra as contas com o saldo de hoje e vê o saldo de cada uma, inclusive negativo e com cheque especial.

**Arquivos desta fase:** `ContaRepository`, `ConciliacaoRepository` (inserir e buscar a última), `MovimentacaoRepository` (soma desde uma data), `CalculoDeSaldo`, `Saldo`, `ContaService`, `ContasController` (contas e saldo), DTOs e erros; `CalculoDeSaldoTest`, `ContaServiceTest`, `ContasIT`.

**O que fazer:**
- `POST /api/contas` (RF01): valida o corpo e cria, numa transação, a conta e a primeira conciliação, com `saldoReal = saldoCalculado = saldoInicial`, divergência zero e `data = dataSaldoInicial` (padrão: hoje, no fuso de São Paulo). Responde 201 com a conta e o saldo.
- `GET /api/contas[?incluirInativas=true]` (RF03): lista com o saldo atual de cada conta.
- `PATCH /api/contas/{id}` (RF04): altera só `apelido`, `instituicao`, `funcao`, `limiteChequeEspecial` e `ativa`, e atualiza `updated_at` (T8). Enviar `tipo` → 422 `CAMPO_NAO_EDITAVEL`. Desativar com saldo é permitido (P5).
- `GET /api/contas/{id}/saldo` (RF05): saldo atual, última conciliação (data e valor) e quantidade de movimentações desde ela. Em conta `CORRENTE`: `chequeEspecialEmUso`, `limiteDisponivel` e `acimaDoLimite` (RN11, P8).
- `CalculoDeSaldo` é uma função pura (sem banco), testada por unitário. O repositório só entrega a base e a soma.
- Saldo negativo nunca é bloqueado (RN11).

**Testes (TDD):**
- [x] Unitário → base R$ 1.000 + crédito R$ 200 − débito R$ 50 → R$ 1.150
- [x] Unitário → corrente com limite R$ 1.000 e saldo −R$ 300 → R$ 300 em uso, R$ 700 disponível, `acimaDoLimite = false`
- [x] Unitário → corrente com limite R$ 1.000 e saldo −R$ 1.200 → R$ 1.000 em uso, R$ 0 disponível, `acimaDoLimite = true`
- [x] Unitário → corrente com saldo positivo → R$ 0 em uso, limite inteiro disponível
- [x] Unitário → poupança com saldo negativo → saldo negativo, sem campos de cheque especial
- [x] Unitário → limite informado em conta que não é corrente → 422 `LIMITE_SO_EM_CONTA_CORRENTE`
- [x] Integração → `POST` com saldo inicial R$ 2.300 → 201; o saldo é R$ 2.300 e existe uma conciliação com divergência zero (critério 1 do PRD)
- [x] Integração → `POST` com apelido repetido (outra caixa) → 409 `APELIDO_EM_USO`
- [x] Integração → `GET /api/contas` não traz inativas; com `incluirInativas=true`, traz
- [x] Integração → `PATCH` com `tipo` → 422; `PATCH` com `ativa=false` em conta com saldo → 200
- [x] Integração → conta inexistente → 404 `CONTA_NAO_ENCONTRADA`
- [x] Integração → sem token → 401 (SEC01)
- [x] Borda → `saldoInicial` com 3 casas decimais → 422; `saldoInicial` negativo → aceito

**Checklist de validação:**
- [x] RF01, RF03, RF04, RF05, RN06, RN09 (aplicação), RN11, SEC01, SEC02, P5, P8 atendidos
- [x] Testes das fases 1–2 passando

**Critério de avanço:** checklist completo → Fase 3.

---

### Fase 3 — Movimentações

**Objetivo:** o usuário transfere entre contas e lança rendimentos, inclusive sem sinal, corrige lançamentos e consulta o extrato.

**Arquivos desta fase:** `MovimentacaoRepository` (inserir com ID, buscar, excluir, listar por período), `MovimentacaoService`, `ContasController` (movimentações e extrato), DTOs e erros; `MovimentacaoServiceTest`, `MovimentacoesIT`.

**O que fazer:**
- `PUT /api/contas/movimentacoes/{id}` com `tipo` `TRANSFERENCIA` ou `RENDIMENTO` (RF10, RF11, P1):
  - ID novo → grava e responde 200 com a movimentação.
  - ID existente com o mesmo conteúdo → 200 com a existente.
  - ID existente com conteúdo diferente → 409 `MOVIMENTACAO_DIVERGENTE`.
  - Outro `tipo` → 422 `TIPO_NAO_PERMITIDO` (os outros só entram pela fachada, RF14).
  - Conta inexistente → 404; conta inativa → 422 `CONTA_INATIVA` (RN08, RN10); origem igual ao destino → 422 `TRANSFERENCIA_PARA_A_MESMA_CONTA`.
  - ID de movimentação excluída → 409 `MOVIMENTACAO_EXCLUIDA` (T9).
- `DELETE /api/contas/movimentacoes/{id}` (RF13, T2, T3): exclusão lógica, preenchendo `deleted_at` e `updated_at`. Responde 204.
- `POST /api/contas/movimentacoes/{id}/restaurar` (RF15, T2, T3): limpa `deleted_at`, atualiza `updated_at` e responde 200 com a movimentação.
- `GET /api/contas/{id}/extrato?de=&ate=` (RF12): movimentações da conta no período, em ordem de data, com sentido (crédito ou débito) e contraparte. Também traz as conciliações do período como marcos e `saldoInicial = saldoEm(de − 1 dia)` e `saldoFinal = saldoEm(ate)`. `de` depois de `ate` → 422 `PERIODO_INVALIDO`.
- Lançamento com atraso vale pela data da movimentação (P2). Data futura é aceita e conta no saldo atual (RN15).

**Testes (TDD):**
- [x] Unitário → mesmo ID e mesmo corpo → nenhuma inserção nova, devolve a existente
- [x] Unitário → mesmo ID com valor diferente → `MOVIMENTACAO_DIVERGENTE`
- [x] Unitário → excluir `SAIDA` → `MOVIMENTACAO_DE_OUTRO_MODULO`; excluir ID inexistente ou já excluído → sem erro
- [x] Unitário → restaurar ID inexistente → 404; restaurar movimentação viva → sem mudança
- [x] Unitário → `PUT` com ID de movimentação excluída → `MOVIMENTACAO_EXCLUIDA`
- [x] Integração → transferência de R$ 1.500 do Nubank para o CDB → Nubank −1.500, CDB +1.500 (critério 2)
- [x] Integração → `PUT` repetido → um registro só, as duas respostas 200 e iguais
- [x] Integração → dois `PUT` simultâneos com o mesmo ID → um registro, nenhuma resposta 500
- [x] Integração → transferência para a mesma conta → 422 (critério 3, lado da aplicação)
- [x] Integração → rendimento de R$ 42 → saldo +42 (critério 5)
- [x] Integração → transferência que deixa a origem negativa → 200 (RN11)
- [x] Integração → transferência com data daqui a 10 dias → 200 e já conta no saldo atual (RN15)
- [x] Integração → movimentação em conta inativa → 422; em conta inexistente → 404
- [x] Integração → excluir transferência → os saldos voltam ao que eram e ela some do extrato, mas a linha continua no banco com `deleted_at` preenchido
- [x] Integração → restaurar a transferência → os saldos e o extrato voltam a contá-la
- [x] Integração → extrato de setembro: só as movimentações do mês, com saldo inicial e final corretos (critério 10)
- [x] Integração → movimentação com data igual à da última conciliação → aparece no extrato e não altera o saldo atual (P2)
- [x] Borda → período sem movimentações → lista vazia e saldo inicial igual ao final

**Checklist de validação:**
- [x] RF10–RF15, RN02, RN04 (aplicação), RN05, RN08, RN10, RN11, RN14, RN15, P1, P2 atendidos
- [x] Testes das fases 1–3 passando

**Critério de avanço:** checklist completo → Fase 4.

---

### Fase 4 — Conciliação

**Objetivo:** o usuário informa o saldo real do extrato e o sistema registra a divergência e passa a calcular a partir dele.

**Arquivos desta fase:** `ConciliacaoRepository` (histórico), `ConciliacaoService`, `ContasController` (conciliações), DTOs e erros; `ConciliacaoServiceTest`, `ConciliacoesIT`.

**O que fazer:**
- `POST /api/contas/{id}/conciliacoes { data, saldoReal, justificativa? }` (RF20):
  - `saldoCalculado = saldoEm(conta, data)`, `divergencia = saldoReal − saldoCalculado`; grava e responde 201.
  - `data` igual ou anterior à da última conciliação → 422 `CONCILIACAO_FORA_DE_ORDEM` (P3), exceto o reenvio idêntico, que responde 200 com a existente (T4).
  - `data` futura → 422 `DATA_FUTURA` (o saldo real é lido de um extrato que já existe).
  - Conta inativa → 422 `CONTA_INATIVA` (RN08).
- `GET /api/contas/{id}/conciliacoes` (RF21): da mais recente para a mais antiga.
- A divergência gravada nunca é recalculada (RN07). Não há endpoint de alteração ou exclusão de conciliação.

**Testes (TDD):**
- [x] Unitário → calculado R$ 800 e real R$ 770 → divergência −R$ 30
- [x] Unitário → data igual à da última → `CONCILIACAO_FORA_DE_ORDEM`; mesmo corpo da última → devolve a existente
- [x] Integração → depois de conciliar com R$ 770, o saldo atual parte de R$ 770 (critério 7)
- [x] Integração → excluir (ou restaurar) uma movimentação anterior à conciliação → a divergência registrada não muda (critério 8)
- [x] Integração → lançar movimentação retroativa → a divergência registrada não muda (critério 8)
- [x] Integração → histórico em ordem decrescente de data, incluindo a conciliação inicial
- [x] Integração → `UPDATE` direto no SQL → exceção do trigger (critério 9; reforça a Fase 1)
- [x] Borda → data futura → 422; conta inativa → 422

**Checklist de validação:**
- [x] RF20, RF21, RN06, RN07, RN08, P3 atendidos
- [x] Testes das fases 1–4 passando

**Critério de avanço:** checklist completo → Fase 5.

---

### Fase 5 — Visão central e fachada

**Objetivo:** o usuário vê a posição geral e o saldo em conta numa chamada só, e os módulos futuros têm por onde lançar e consultar.

**Arquivos desta fase:** `FontesDeDivida`, `ConsolidadoService`, `ContasFacade`, `ContasFacadeImpl`, `ContasController` (consolidado), DTOs; `ConsolidadoServiceTest`, `ContasFacadeIT`, `ConsolidadoIT`; `Makefile`.

**O que fazer:**
- `GET /api/contas/consolidado` (RF06, RN12, RN13):
  - `posicaoGeral = Σ saldo atual de todas as contas ativas − dividas`; pode ser negativa.
  - `dividas = Σ FontesDeDivida.totalDevido()` de todas as implementações; sem nenhuma, zero (RF32).
  - `saldoEmConta = Σ max(saldo, 0)` das contas `CORRENTE` ativas (P7).
  - A lista de contas, cada uma com o saldo e, nas correntes, o cheque especial.
  - O cheque especial não é descontado de novo (RN11). Nenhuma categoria de reserva entra (RN01).
- `ContasFacade` (RF30): `saldoAtual`, `registrarEntrada`, `registrarSaida`, `registrarPagamentoFatura` e `contasDeReserva` (só `RESERVA` ativas). O registro é idempotente por referência (T5) e recusa conta inativa (RN08).
- Makefile: alvos `conta`, `transferir`, `conciliar` e `consolidado`, que leem o token sem eco, como `make senha`.

**Testes (TDD):**
- [ ] Unitário → contas de R$ 2.300, R$ 9.000 e −R$ 300 (corrente), sem dívidas → posição R$ 11.000; saldo em conta R$ 2.300
- [ ] Unitário → mesma situação com uma fonte de dívida de R$ 12.000 → posição −R$ 1.000
- [ ] Unitário → conta inativa fica fora das duas somas
- [ ] Unitário → corrente negativa entra como zero no saldo em conta e negativa na posição geral, sem desconto duplo do cheque especial
- [ ] Integração → transferência do Nubank para o CDB não muda a posição geral e reduz o saldo em conta (RN02)
- [ ] Integração (fachada) → `registrarSaida` debita a conta; repetir com o mesmo `gastoId` não debita de novo
- [ ] Integração (fachada) → `registrarEntrada` e `registrarPagamentoFatura` movem o saldo no sentido certo
- [ ] Integração (fachada) → `contasDeReserva` devolve só `RESERVA` ativas
- [ ] Integração (fachada) → registro em conta inativa → `CONTA_INATIVA`
- [ ] Integração → `GET /api/contas/consolidado` sem token → 401
- [ ] Modularidade → um módulo de teste não acessa as classes internas de `contas`

**Checklist de validação:**
- [ ] RF06, RF30, RF31, RF32, RN01, RN03, RN12, RN13, P6 (ponto de ligação), P7 atendidos
- [ ] Todos os critérios de aceitação do PRD (seção 10) marcados
- [ ] Gate de cobertura de 90% em linhas e ramos passando (RNF05)
- [ ] Testes das fases 1–5 passando

**Critério de avanço:** checklist completo → Fase 6.

---

### Fase 6 — Padrão de datas nas tabelas das fases 1–2

**Objetivo:** as tabelas da calculadora e do auth seguem o mesmo padrão de `created_at`, `updated_at` e `deleted_at`, e a simulação passa a ter exclusão lógica e restauração, sem mudar os contratos que o app já usa.

**Arquivos desta fase:** `[NEW] V005__padrao_datas.sql`; `[MOD]` `SimulacaoRepository`, `HistoricoService`, `HistoricoController`, `DispositivoRepository`, `UsuarioRepository`, `TentativaRepository`; testes existentes de histórico e auth ajustados onde citam colunas; `[NEW] SimulacaoExcluidaException`, `HistoricoExclusaoIT`.

**O que fazer:**
- `V005` (RF40, RF42, RF43):
  - `calculadora.simulacoes`: `sincronizada_em` → `created_at`; novas `updated_at` (`DEFAULT now()`) e `deleted_at`. Índices de `bem` e `data` passam a ser parciais (`WHERE deleted_at IS NULL`).
  - `auth.usuarios`: `criado_em` → `created_at`; nova `updated_at`.
  - `auth.dispositivos`: `criado_em` → `created_at`; nova `updated_at`. `revogado_em` continua como está (revogação definitiva).
  - `auth.tentativas_login`: `em` → `created_at`. Os índices acompanham a coluna renomeada.
  - `event_publication` (Spring Modulith) não é tocada: o schema é do framework.
- Histórico da calculadora (RF41):
  - `DELETE` preenche `deleted_at` e `updated_at`; excluir o que não existe ou já foi excluído → 204.
  - `POST /api/calculadora/historico/{id}/restaurar` → 200 com a simulação; inexistente → 404.
  - Listagem, busca e comparação filtram `deleted_at IS NULL`.
  - Estimativa nova apontando para âncora excluída → 422 `ANCORA_INVALIDA`.
  - A proteção da âncora (D3) passa a contar só as estimativas não excluídas.
  - `PUT` com ID de simulação excluída → 409 `SIMULACAO_EXCLUIDA` (mesma lógica da T9).
  - `PATCH` de anotação atualiza `updated_at`.
- Auth (RF42): `updated_at` é preenchido na troca de senha e na revogação de dispositivo. `ultimo_uso` e `ultimo_acesso` continuam com coluna própria e não mexem em `updated_at`, para a escrita de no máximo uma vez por minuto não virar "alteração".
- A API não muda: `sincronizadaEm` e `criadoEm` continuam com o mesmo nome no JSON (RF40, RF43).

**Testes (TDD):**
- [ ] Integração → excluir simulação → some do `GET` e da comparação, mas a linha continua no banco com `deleted_at`
- [ ] Integração → restaurar → volta ao `GET` e à comparação
- [ ] Integração → excluir âncora com estimativa viva → 409 `SIMULACAO_E_ANCORA`; com a estimativa já excluída → 204
- [ ] Integração → estimativa nova com âncora excluída → 422 `ANCORA_INVALIDA`
- [ ] Integração → `PUT` com ID excluído → 409 `SIMULACAO_EXCLUIDA`
- [ ] Banco → `UPDATE` de `deleted_at` numa simulação → permitido pelo trigger de valores
- [ ] Integração → `PATCH` de anotação muda `updated_at` e não `created_at`
- [ ] Integração → troca de senha e revogação atualizam `updated_at`; uso do token não
- [ ] Contrato → `sincronizadaEm` e `criadoEm` continuam no JSON com os mesmos valores
- [ ] Regressão → toda a suíte das fases anteriores (calculadora e auth) passa sem mudar o comportamento, exceto a exclusão de simulação

**Checklist de validação:**
- [ ] RF40–RF43, RN14 atendidos
- [ ] Gate de cobertura de 90% em linhas e ramos passando (RNF05)
- [ ] Testes das fases 1–6 passando

**Critério de avanço:** checklist completo → módulo pronto para validação local.

---

## 5. Ordem de Execução e Dependências

```
Fase 1 → Fase 2 → Fase 3 → Fase 4 → Fase 5 → Fase 6
```

- A Fase 6 não depende do módulo `contas`: poderia vir antes. Fica por último para não misturar a mudança nas tabelas antigas com a criação do módulo novo, e para a `V004` (contas) já nascer no padrão.

- A Fase 2 já grava conciliação (a inicial), mas o endpoint de conciliação é da Fase 4. A regra de saldo é a mesma nas duas.
- A Fase 3 precisa da função `saldoEm` da Fase 2 para o extrato.
- A Fase 5 lê saldos de todas as contas: depende das fases 2 a 4 estarem estáveis.
- Nenhuma fase roda em paralelo: as migrations e o controller são compartilhados.

## 6. Contratos e Interfaces

### 6.1 Schema (`V004__contas.sql`)

```sql
CREATE SCHEMA contas;

CREATE TABLE contas.contas (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
    apelido                 TEXT NOT NULL CHECK (btrim(apelido) <> ''),
    instituicao             TEXT NOT NULL,
    tipo                    TEXT NOT NULL CHECK (tipo IN ('CORRENTE','POUPANCA','INVESTIMENTO','CARTEIRA','BENEFICIO')),
    funcao                  TEXT NOT NULL CHECK (funcao IN ('GIRO','RESERVA')),
    limite_cheque_especial  NUMERIC(19,2) NOT NULL DEFAULT 0,
    ativa                   BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT limite_so_em_corrente CHECK (limite_cheque_especial >= 0
        AND (tipo = 'CORRENTE' OR limite_cheque_especial = 0))
);
CREATE UNIQUE INDEX contas_apelido_unico ON contas.contas (lower(btrim(apelido)));

CREATE TABLE contas.movimentacoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    tipo            TEXT NOT NULL CHECK (tipo IN ('ENTRADA','SAIDA','TRANSFERENCIA','PAGAMENTO_FATURA','RENDIMENTO')),
    conta_origem    UUID REFERENCES contas.contas (id),
    conta_destino   UUID REFERENCES contas.contas (id),
    valor           NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    data            DATE NOT NULL,
    ganho_id        UUID,
    gasto_id        UUID,
    cartao_id       UUID,
    fatura_comp     DATE CHECK (fatura_comp IS NULL OR EXTRACT(DAY FROM fatura_comp) = 1),
    motivo          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT direcao_coerente CHECK (
        (tipo IN ('ENTRADA','RENDIMENTO')         AND conta_destino IS NOT NULL AND conta_origem IS NULL) OR
        (tipo IN ('SAIDA','PAGAMENTO_FATURA')     AND conta_origem  IS NOT NULL AND conta_destino IS NULL) OR
        (tipo = 'TRANSFERENCIA' AND conta_origem IS NOT NULL AND conta_destino IS NOT NULL
                                AND conta_origem <> conta_destino))
);
-- Índices só sobre as movimentações vivas: as excluídas não entram em cálculo nem bloqueiam relançamento.
CREATE INDEX movimentacoes_origem_idx  ON contas.movimentacoes (conta_origem, data DESC)  WHERE deleted_at IS NULL;
CREATE INDEX movimentacoes_destino_idx ON contas.movimentacoes (conta_destino, data DESC) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX movimentacoes_gasto_unico  ON contas.movimentacoes (gasto_id)
    WHERE gasto_id IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX movimentacoes_ganho_unico  ON contas.movimentacoes (ganho_id)
    WHERE ganho_id IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX movimentacoes_fatura_unica ON contas.movimentacoes (cartao_id, fatura_comp)
    WHERE cartao_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE contas.conciliacoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    conta_id        UUID NOT NULL REFERENCES contas.contas (id),
    data            DATE NOT NULL,
    saldo_real      NUMERIC(19,2) NOT NULL,
    saldo_calculado NUMERIC(19,2) NOT NULL,
    divergencia     NUMERIC(19,2) NOT NULL,
    justificativa   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conta_id, data)
);
CREATE INDEX conciliacoes_conta_data_idx ON contas.conciliacoes (conta_id, data DESC);

CREATE TRIGGER conciliacoes_imutaveis
    BEFORE UPDATE OR DELETE ON contas.conciliacoes
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();
```

> O `DELETE` em conciliação é bloqueado, então uma conta com conciliação não pode ser apagada: desativar é o caminho (RF04). Não existe `DELETE /api/contas/{id}`.

### 6.2 Tipos públicos

```java
public record ContaId(UUID valor) {}

public enum TipoConta { CORRENTE, POUPANCA, INVESTIMENTO, CARTEIRA, BENEFICIO }
public enum Funcao { GIRO, RESERVA }

public record Conta(ContaId id, String apelido, String instituicao, TipoConta tipo, Funcao funcao,
                    Dinheiro limiteChequeEspecial, boolean ativa) {}

public interface ContasFacade {
    Dinheiro saldoAtual(ContaId conta);
    void registrarEntrada(ContaId destino, UUID ganhoId, Dinheiro valor, LocalDate data);
    void registrarSaida(ContaId origem, UUID gastoId, Dinheiro valor, LocalDate data);
    void registrarPagamentoFatura(ContaId origem, UUID cartaoId, Competencia fatura, Dinheiro valor, LocalDate data);
    List<Conta> contasDeReserva();
}

/** Implementada por cartoes (fase 5 do roadmap). Sem implementação, a dívida é zero. */
public interface FontesDeDivida {
    Dinheiro totalDevido();
}
```

### 6.3 Modelo interno

```java
sealed interface Movimentacao {
    MovimentacaoId id(); Dinheiro valor(); LocalDate data();
    record Entrada(MovimentacaoId id, ContaId destino, UUID ganhoId, Dinheiro valor, LocalDate data) implements Movimentacao {}
    record Saida(MovimentacaoId id, ContaId origem, UUID gastoId, Dinheiro valor, LocalDate data) implements Movimentacao {}
    record Transferencia(MovimentacaoId id, ContaId origem, ContaId destino, Dinheiro valor, LocalDate data, String motivo) implements Movimentacao {}
    record PagamentoFatura(MovimentacaoId id, ContaId origem, UUID cartaoId, Competencia fatura, Dinheiro valor, LocalDate data) implements Movimentacao {}
    record Rendimento(MovimentacaoId id, ContaId destino, Dinheiro valor, LocalDate data) implements Movimentacao {}
}

record Saldo(Dinheiro atual, LocalDate baseEm, Dinheiro baseValor, int movimentacoesDesdeABase,
             ChequeEspecial chequeEspecial) {            // nulo fora de conta corrente
    record ChequeEspecial(Dinheiro limite, Dinheiro emUso, Dinheiro disponivel, boolean acimaDoLimite) {}
}
// v1.3 (contrato original, substituído na Fase 2):
// record Saldo(Dinheiro atual, LocalDate baseEm, Dinheiro baseValor, int movimentacoesDesdeABase,
//              Dinheiro chequeEspecialEmUso, Dinheiro limiteDisponivel, boolean acimaDoLimite) {}
```

### 6.4 API

| Método e caminho | Corpo | Sucesso | Erros |
|---|---|---|---|
| `POST /api/contas` | `{ apelido, instituicao, tipo, funcao, saldoInicial, dataSaldoInicial?, limiteChequeEspecial? }` | 201 conta + saldo | 422 `DADOS_INVALIDOS`, `LIMITE_SO_EM_CONTA_CORRENTE`; 409 `APELIDO_EM_USO` |
| `GET /api/contas[?incluirInativas]` | — | 200 lista com saldo | — |
| `PATCH /api/contas/{id}` | campos editáveis | 200 conta | 404 `CONTA_NAO_ENCONTRADA`; 422 `CAMPO_NAO_EDITAVEL`, `LIMITE_SO_EM_CONTA_CORRENTE`; 409 `APELIDO_EM_USO` |
| `GET /api/contas/{id}/saldo` | — | 200 `Saldo` | 404 |
| `PUT /api/contas/movimentacoes/{id}` | `{ tipo, origem?, destino?, valor, data, motivo? }` | 200 movimentação | 404; 409 `MOVIMENTACAO_DIVERGENTE`, `MOVIMENTACAO_EXCLUIDA`; 422 `TIPO_NAO_PERMITIDO`, `CONTA_INATIVA`, `TRANSFERENCIA_PARA_A_MESMA_CONTA`, `DADOS_INVALIDOS` |
| `DELETE /api/contas/movimentacoes/{id}` | — | 204 (exclusão lógica) | 422 `MOVIMENTACAO_DE_OUTRO_MODULO` |
| `POST /api/contas/movimentacoes/{id}/restaurar` | — | 200 movimentação | 404 `MOVIMENTACAO_NAO_ENCONTRADA`; 422 `MOVIMENTACAO_DE_OUTRO_MODULO` |
| `GET /api/contas/{id}/extrato?de&ate` | — | 200 `{ saldoInicial, saldoFinal, linhas, conciliacoes }` | 404; 422 `PERIODO_INVALIDO` |
| `POST /api/contas/{id}/conciliacoes` | `{ data, saldoReal, justificativa? }` | 201 (200 no reenvio idêntico) | 404; 422 `CONCILIACAO_FORA_DE_ORDEM`, `DATA_FUTURA`, `CONTA_INATIVA` |
| `GET /api/contas/{id}/conciliacoes` | — | 200 lista | 404 |
| `GET /api/contas/consolidado` | — | 200 `{ posicaoGeral, dividas, saldoEmConta, contas }` | — |

Todos: 401 sem token (SEC01). Valores monetários como string decimal.

## 7. Riscos Técnicos

| Risco | Fase afetada | Mitigação |
|---|---|---|
| A regra de saldo se repetir em lugares diferentes (saldo, extrato, conciliação, consolidado) e divergir | 2–5 | Uma função só (`saldoEm`), com `CalculoDeSaldo` puro e coberto por unitário |
| Consolidado com N contas fazer N+1 consultas | 5 | Uma consulta agregada por conta (base + soma) para todas as contas; o volume é pequeno (usuário único) |
| Corrida entre dois `PUT` com o mesmo ID | 3 | `ON CONFLICT DO NOTHING` e comparação de conteúdo, como no histórico |
| `ModularidadeTest` quebrar quando `fluxo` e `cartoes` usarem a fachada | futuras | Só tipos do pacote base são públicos; o teste da Fase 5 já verifica |
| Renomear colunas das fases 1–2 quebrar SQL escrito à mão nos repositórios ou o mapeamento da API | 6 | A suíte inteira (217+ testes) roda antes do commit; a API mantém os nomes de campo (`sincronizadaEm`, `criadoEm`), só o SQL muda |
| Exclusão lógica da simulação conflitar com o trigger `simulacao_valores_imutaveis` | 6 | O trigger compara só as colunas de valor; `deleted_at` e `updated_at` ficam de fora, e um teste confirma |

## 8. Rastreabilidade PRD → SPEC

| Requisito PRD | Fase | Observação |
|---|---|---|
| RF01 | 2 | Conta + conciliação inicial na mesma transação |
| RF02 | 1 | Enums e `CHECK` |
| RF03, RF04, RF05 | 2 | — |
| RF06 | 5 | Visão central |
| RF10, RF11 | 3 | Via `PUT /movimentacoes/{id}` (P1) |
| RF12, RF13, RF14, RF15 | 3 | T2, T3, T9; exclusão lógica |
| RF20, RF21 | 4 | T4 |
| RF30 | 5 | T1, T5 |
| RF31 | 1, 5 | `ModularidadeTest` |
| RF32 | 5 | Lista vazia de implementações → dívida zero |
| RNF01, RNF02 | 1 | — |
| RNF03 | 2 | Base + soma, sem percorrer histórico |
| RNF04 | 2–5 | Segurança existente; formato de erro existente |
| RNF05 | todas | Gate JaCoCo |
| RN01 | 5 | — |
| RN02 | 1, 3, 5 | Sentido por conta; teste da posição geral |
| RN03 | 1, 5 | Pagamento de fatura é débito, não gasto (só a fachada lança) |
| RN04 | 1 (banco), 3 (aplicação) | — |
| RN05 | 1, 3 | — |
| RN06 | 2, 4 | `saldoEm` |
| RN07 | 1 (trigger), 4 | — |
| RN08 | 3, 4, 5 | — |
| RN09 | 1 (banco), 2 (aplicação) | T6 |
| RN10 | 3 | — |
| RN11 | 2, 3, 5 | Sem bloqueio; cheque especial só em corrente |
| RN12, RN13 | 5 | — |
| RN14 | 1 (schema), 2–3 (aplicação), 6 (tabelas antigas) | T8 |
| RN15 | 3 | Data futura aceita |
| RF40, RF41 | 6 | Simulação com exclusão lógica e restauração |
| RF42, RF43 | 6 | Auth: só renomeação e `updated_at` |
| SEC01 | 2–5 | Teste de 401 em cada fase |
| SEC02 | 2 | Nenhum log com valor ou saldo |
| P1 | 3 | — |
| P2 | 2, 3 | `saldoEm` usa `data > base` |
| P3 | 4 | T4 |
| P5 | 2 | — |
| P6 | 5 | Só o ponto de ligação |
| P7 | 5 | — |
| P8 | 2 | `acimaDoLimite` |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|--------|------------|----------------------------|
| 1.0 | 2026-11-14 | Criação inicial: 5 fases aprovadas; decisões técnicas T1–T7; uma pendência (data futura de movimentação) |
| 1.1 | 2026-11-14 | PRD v1.7: exclusão lógica e restauração de movimentação (RF13, RF15); colunas `created_at`, `updated_at` e `deleted_at` conforme a tabela da seção 1 (RN14); índices parciais só sobre movimentações vivas; decisões T8 e T9 |
| 1.2 | 2026-11-14 | PRD v1.8: data futura aceita (RN15, pendência resolvida); nova **Fase 6**, com o padrão de datas nas tabelas da calculadora e do auth e exclusão lógica de simulação (RF40–RF43, `V005`). Status aprovado |
| 1.3 | 2026-11-14 | Fase 1 concluída: `V004__contas.sql` e modelo (`Movimentacao` selada com `tipo()`, `sentidoPara()` e `efeitoNoSaldo()`). Red: compilação sem os tipos e `SchemaContasIT` com 15 falhas sem a migration; green: 321 testes, cobertura 98,8% de linhas e 97,9% de ramos. Testes extras além da lista: `ContaTest` (campos obrigatórios), checagem de `fatura_comp` no dia 1 e de apelido em branco. Makefile sem alvo novo nesta fase (os alvos de conta entram na Fase 5) |
| 1.4 | 2026-11-14 | Fase 2 concluída. Desvios: (a) o cheque especial do `Saldo` virou um objeto aninhado `chequeEspecial { limite, emUso, disponivel, acimaDoLimite }`, nulo fora de conta corrente, em vez de três campos soltos (o JSON fica sem campos sem sentido em poupança e investimento); (b) os valores do `POST /api/contas` chegam como `BigDecimal` com `@Digits(fraction = 2)`, porque o `Dinheiro` arredondaria 3 casas em silêncio; (c) a resposta do saldo traz `ultimaConciliacao { data, saldoReal }`; (d) `GET /api/contas` lê o saldo conta a conta (consulta agregada fica para a Fase 5, como no risco da seção 7). Red: compilação sem as 12 classes da fase; green: 354 testes, cobertura 99,0% de linhas e 97,4% de ramos |
| 1.5 | 2026-11-14 | Fase 3 concluída. Decisão T10 (saldo antes da primeira conciliação é zero). Desvios de forma: os endpoints de movimentação ficaram num `MovimentacoesController` próprio, sob o mesmo `/api/contas`; o extrato devolve `{ conta, de, ate, saldoInicial, saldoFinal, linhas[{ id, tipo, data, valor, sentido, contraparte, motivo }], conciliacoes[{ data, saldoReal, saldoCalculado, divergencia, justificativa }] }`. Teste extra: `MovimentacaoRepositoryIT`, ida e volta no banco dos cinco tipos. Red: compilação sem as 16 classes e métodos da fase; green: 389 testes, cobertura 98,8% de linhas e 96,0% de ramos |
| 1.6 | 2026-11-14 | Fase 4 concluída. Ordem das regras do `POST /conciliacoes`: conta existe (404) → reenvio idêntico da última devolve 200 (antes de qualquer outra regra, como no reenvio de movimentação) → conta ativa → data não futura → data posterior à última. Corrida entre dois envios do mesmo dia: a violação de `UNIQUE (conta_id, data)` cai na regra de reenvio. Endpoints num `ConciliacoesController` próprio. Red: compilação sem `ConciliacaoService` e as 2 exceções; green: 406 testes, cobertura 98,7% de linhas e 96,1% de ramos |
