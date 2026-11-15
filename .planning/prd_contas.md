# PRD — Azimute Core: Módulo `contas`, parte 1 (Fase 3)

**Data:** 2026-11-14
**Status:** aprovado

**Fontes:** `assistente-docs/00-arquitetura.md` (ordem de construção, fase 3), `06-modulo-contas.md`. Quando este PRD diverge da doc, prevalece o PRD; as divergências são decisões novas registradas no Histórico de Revisões.

---

## 1. Contexto e Problema

As fases 1 e 2 estão prontas: kernel `comum` (`Dinheiro`, `Competencia`, `Percentual`), calculadora com histórico, e `auth` com token por dispositivo e limite de tentativas.

O próximo módulo pela ordem de construção é `contas`. Ele é **fundacional**: `fluxo` (fase 4), `cartoes` (fase 5) e `reserva` (fase 8) registram valores que precisam estar em alguma conta real. Se `contas` viesse depois de `fluxo`, os gastos nasceriam sem conta de origem e exigiriam migração de dados.

**Problema concreto:** o dinheiro do usuário está espalhado em várias contas (corrente, poupança, CDB, carteira, VR/VA) e hoje não há um lugar que responda "quanto tenho, e onde". O saldo calculado por um app sempre se afasta do real (um pix esquecido de R$ 30 erra o saldo para sempre), então o sistema precisa de um mecanismo para voltar ao valor real sem perder a informação de que houve diferença.

O sistema é de **usuário único**. A escolha nesta fase é pelo mais simples que resolve o problema.

## 2. Objetivo

Ao fim da fase 3, o backend deve permitir:

1. Cadastrar as contas reais com o saldo atual de cada uma, e desativar as que deixarem de ser usadas.
2. Registrar transferências entre contas e rendimentos de aplicações, sem que transferência conte como gasto.
3. Conciliar uma conta com o extrato: informar o saldo real, ver a divergência em relação ao calculado, e adotar o real como nova base, **guardando a divergência**.
4. Ver **um número central, a posição geral**: todo o dinheiro do usuário, somando todas as contas e (a partir da fase 5) descontando o que deve no crédito. É a informação principal do módulo e pode ser negativa. O saldo de cada conta e o extrato existem como detalhe por trás desse número.
5. Corrigir uma movimentação lançada errado, excluindo-a de forma reversível (exclusão lógica).
6. Deixar pronta a fachada que `fluxo` e `cartoes` vão usar para lançar entradas, saídas e pagamentos de fatura.

## 3. Requisitos Funcionais

### 3.1 Contas

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF01 | `POST /api/contas { apelido, instituicao, tipo, funcao, saldoInicial, dataSaldoInicial?, limiteChequeEspecial? }` (o limite só é aceito em conta `CORRENTE`; padrão zero) cria a conta e registra a **primeira conciliação** com `saldoReal = saldoInicial`, `saldoCalculado = saldoInicial` e divergência zero, na data informada (padrão: hoje) | Alta |
| RF02 | `tipo` ∈ `CORRENTE`, `POUPANCA`, `INVESTIMENTO`, `CARTEIRA`, `BENEFICIO`; `funcao` ∈ `GIRO`, `RESERVA` | Alta |
| RF03 | `GET /api/contas` lista as contas com o saldo atual de cada uma; por padrão só as ativas, com filtro para incluir as inativas | Alta |
| RF04 | `PATCH /api/contas/{id}` altera `apelido`, `instituicao`, `funcao`, `limiteChequeEspecial` (só `CORRENTE`) e `ativa`. `tipo` não muda depois de criado | Alta |
| RF05 | `GET /api/contas/{id}/saldo` devolve o saldo atual (pode ser negativo), a data e o valor da última conciliação, e quantas movimentações entraram desde ela. Em conta `CORRENTE`, também `chequeEspecialEmUso` (quanto do limite está sendo usado) e `limiteDisponivel` (RN11) | Alta |
| RF06 | `GET /api/contas/consolidado` é a **visão central** do módulo (RN12, RN13). Devolve, numa resposta só, os valores globais e o saldo de cada conta que os compõe. Nunca inclui categoria de reserva (RN01): | Alta |
| | • **`posicaoGeral`** (o número principal): soma de todas as contas ativas, de todos os tipos, **menos as dívidas de crédito**. Pode ser negativa. Responde "no geral, estou positivo ou negativo?". Nesta fase não há dívidas (o módulo `cartoes` é da fase 5), então ela é igual à soma das contas; o contrato já nasce com o campo `dividas` (zero) para não mudar depois (RF32) | |
| | • **`saldoEmConta`** (secundário): soma dos saldos **positivos** das contas `CORRENTE` ativas, que é o dinheiro disponível na conta corrente. Conta corrente negativa entra como zero aqui (o negativo é cheque especial e já aparece na posição geral) | |

### 3.2 Movimentações

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF10 | `POST /api/contas/transferencias { origem, destino, valor, data, motivo? }` registra uma transferência entre duas contas do usuário | Alta |
| RF11 | `POST /api/contas/{id}/rendimentos { valor, data }` credita um rendimento na conta | Alta |
| RF12 | `GET /api/contas/{id}/extrato?de=&ate=` lista as movimentações da conta no período, em ordem de data, com o sentido (crédito ou débito) do ponto de vista da conta, e o saldo no início e no fim do período | Alta |
| RF13 | `DELETE /api/contas/movimentacoes/{id}` faz **exclusão lógica**: a movimentação recebe a data de exclusão (`deleted_at`) e deixa de contar no saldo, no extrato e nas somas, mas continua no banco e pode ser restaurada. As divergências já registradas não mudam (RN07) | Alta |
| RF15 | `POST /api/contas/movimentacoes/{id}/restaurar` desfaz a exclusão lógica, e a movimentação volta a contar | Média |
| RF14 | Os tipos de movimentação são `ENTRADA`, `SAIDA`, `TRANSFERENCIA`, `PAGAMENTO_FATURA` e `RENDIMENTO`. Nesta fase só `TRANSFERENCIA` e `RENDIMENTO` têm endpoint; os outros três só entram pela fachada (RF30) | Alta |

### 3.3 Conciliação

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF20 | `POST /api/contas/{id}/conciliacoes { data, saldoReal, justificativa? }` calcula o saldo na data, grava `saldoCalculado`, `saldoReal` e `divergencia = saldoReal − saldoCalculado`, e passa a usar essa conciliação como base | Alta |
| RF21 | `GET /api/contas/{id}/conciliacoes` lista o histórico de conciliações da conta, da mais recente para a mais antiga, com a divergência de cada uma | Alta |

### 3.4 Fachada para os outros módulos

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF30 | Interface pública `ContasFacade` com `saldoAtual(conta)`, `registrarEntrada(conta, ganho, valor, data)`, `registrarSaida(conta, gasto, valor, data)`, `registrarPagamentoFatura(conta, cartao, competencia, valor, data)` e `contasDeReserva()`. Testada nesta fase, usada a partir da fase 4 | Alta |
| RF31 | `ContaId` é público para os outros módulos; o resto do módulo é interno, verificado pelo Spring Modulith | Alta |
| RF32 | Interface `FontesDeDivida`, definida por `contas` e implementada por `cartoes` a partir da fase 5, que informa o total devido no crédito. Sem implementação, a dívida é zero. Assim a seta de dependência continua apontando para `contas` (como a `FontesDeProjecao` da doc 06) | Média |

### 3.5 Padrão de datas nas tabelas das fases 1–2

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF40 | `calculadora.simulacoes` passa a seguir a RN14: `created_at` (a atual `sincronizada_em`, renomeada; a API continua expondo `sincronizadaEm`), `updated_at` (anotação) e `deleted_at` | Média |
| RF41 | `DELETE /api/calculadora/historico/{id}` passa a ser **exclusão lógica**, com `POST /api/calculadora/historico/{id}/restaurar`. Simulação excluída some da listagem e da comparação e não pode ser âncora de estimativa nova; a regra de não excluir âncora (D3 do PRD anterior) passa a considerar só estimativas não excluídas | Média |
| RF42 | `auth.usuarios` e `auth.dispositivos`: `criado_em` vira `created_at` e entra `updated_at`. Sem `deleted_at`: o usuário é único e a revogação de dispositivo continua **definitiva** (`revogado_em`), por segurança | Baixa |
| RF43 | `auth.tentativas_login`: a coluna `em` vira `created_at` (é um log, só inserção). Os contratos da API não mudam | Baixa |

## 4. Requisitos Não Funcionais

Valem os da fundação (Java 25, Spring Boot 4.1, Spring Modulith, PostgreSQL 18, JdbcClient, Flyway, fuso `America/Sao_Paulo`, Testcontainers). Específicos desta fase:

| ID | Requisito | Categoria |
|-------|---------------------------------------------------|-----------------|
| RNF01 | Schema próprio `contas`, criado por migration Flyway (`V004`) | Manutenibilidade |
| RNF02 | Movimentação modelada como `sealed interface` com um `record` por tipo, para que todo `switch` sobre movimentações trate os cinco casos explicitamente | Manutenibilidade |
| RNF03 | Saldo atual de uma conta: uma leitura da última conciliação e uma soma das movimentações posteriores, sem percorrer o histórico inteiro | Performance |
| RNF04 | Todos os endpoints exigem token de dispositivo (fase 2) e usam o formato único de erro `{ erro, mensagem, detalhes }` | Compatibilidade |
| RNF05 | Cobertura mínima de 90% de linhas e de ramos (gate JaCoCo já existente), com testes unitários (sem banco) e de integração (Testcontainers) | Manutenibilidade |

## 5. Regras de Negócio

- **RN01 — Duas camadas.** Saldo de conta (onde o dinheiro está) nunca é somado com saldo de categoria de reserva (para que o dinheiro é). Patrimônio total usa só contas.
- **RN02 — Transferência não é gasto.** Move dinheiro entre contas e não altera a posição geral (pode alterar o saldo em conta, se envolver uma conta corrente).
- **RN03 — Pagamento de fatura não é gasto.** É liquidação de compra já registrada em outro módulo.
- **RN04 — Direção coerente**, garantida por constraint no banco:
  - `ENTRADA` e `RENDIMENTO`: só conta de destino;
  - `SAIDA` e `PAGAMENTO_FATURA`: só conta de origem;
  - `TRANSFERENCIA`: origem e destino, diferentes entre si.
- **RN05 — Valor positivo.** Toda movimentação tem `valor > 0`; o sentido vem do tipo, não do sinal.
- **RN06 — Saldo atual** = saldo real da última conciliação + créditos − débitos das movimentações **posteriores** a ela (ver P2 sobre movimentações no mesmo dia).
- **RN07 — Divergência é fato histórico.** É gravada como coluna no momento da conciliação e nunca recalculada. Excluir ou lançar movimentação retroativa não altera divergências já registradas. A conciliação não é editada nem excluída, garantido pelo trigger `impedir_alteracao()` que já existe desde a V001.
- **RN08 — Conta inativa** não recebe movimentação nova nem conciliação, mas continua aparecendo no histórico e no extrato.
- **RN09 — Apelido único** entre as contas, ignorando maiúsculas e espaços nas pontas.
- **RN10 — Movimentação em conta inexistente** é rejeitada com 404; em conta inativa, com 422.
- **RN11 — Saldo negativo e cheque especial.** Qualquer conta pode ficar com saldo calculado negativo, e nenhuma movimentação é bloqueada por isso. Em conta `CORRENTE`, saldo negativo **é uso do cheque especial**: `chequeEspecialEmUso = min(−saldo, limite)` quando o saldo é negativo, e zero caso contrário; `limiteDisponivel = limite − chequeEspecialEmUso`. O cheque especial não é somado de novo na posição geral: o saldo negativo da conta já entra nela, descontando (somar o uso como dívida contaria duas vezes). Saldo negativo além do limite: P8.
- **RN12 — A posição geral é a variável global do dinheiro.** Um único valor, que soma todas as contas ativas de todos os tipos e modalidades (corrente, poupança, investimento, carteira, benefício) e subtrai as dívidas de crédito. Ela é a visão central do sistema, não uma conta a mais. Fica negativa quando o que existe em todas as contas não cobre o que se deve: é esse número que diz se o usuário está positivo ou negativo, e não o saldo de uma conta isolada.
- **RN13 — Hierarquia das leituras.** (1) posição geral, a principal; (2) saldo em conta, a soma dos saldos positivos das contas correntes, secundária; (3) saldo de cada conta, o detalhe. As três são calculadas a partir das mesmas contas e sempre batem entre si.
- **RN14 — Datas de controle.** Toda tabela do módulo tem `created_at`; as que podem mudar têm `updated_at`; as que podem ser excluídas têm `deleted_at` (exclusão lógica). Registro com `deleted_at` preenchido não entra em nenhum cálculo nem listagem. Padrão do projeto: vale para este módulo e, pelos RF40–RF43, para as tabelas das fases 1–2.
- **RN15 — Data futura é aceita.** Transferência e rendimento podem ter data futura (pix agendado, resgate previsto) e contam no saldo atual desde já.

## 6. Requisitos de Segurança

- **SEC01:** Todos os endpoints do módulo exigem `Authorization: Bearer <token>` (fase 2); sem token → 401.
- **SEC02:** Valores e saldos não são escritos no log.

## 7. Casos de Uso

### Fluxo Principal A — Cadastrar as contas
1. O usuário cria "Nubank" (`CORRENTE`, `GIRO`) com saldo inicial de R$ 2.300.
2. O sistema cria a conta e a primeira conciliação, e o saldo atual passa a ser R$ 2.300.

### Fluxo Principal B — Transferir para a reserva
1. O usuário transfere R$ 1.500 do Nubank para "CDB Liquidez" (`INVESTIMENTO`, `RESERVA`).
2. O Nubank cai R$ 1.500, o CDB sobe R$ 1.500. A posição geral não muda; o saldo em conta cai R$ 1.500, porque o dinheiro saiu do dia a dia e foi guardado.

### Fluxo Principal C — Conciliar
1. O usuário abre o app do banco e informa que o Nubank tem R$ 770 hoje.
2. O sistema calculava R$ 800 e registra a divergência de −R$ 30, com justificativa opcional.
3. A partir daí, o saldo atual parte de R$ 770.

### Fluxo Principal D — Corrigir um lançamento
1. O usuário lançou uma transferência de R$ 150 em vez de R$ 15.
2. Ele exclui a movimentação e lança de novo com o valor certo.

### Fluxos Alternativos
- **Alternativo A:** transferência com origem igual ao destino → 422.
- **Alternativo B:** movimentação em conta inativa → 422; em conta inexistente → 404.
- **Alternativo C:** apelido repetido na criação ou na alteração → 409.
- **Alternativo D:** tentativa de alterar ou excluir uma conciliação diretamente no banco → erro do trigger.

### Casos de Borda
- Valor zero, negativo ou com mais de 2 casas decimais → 422.
- Extrato com `de` depois de `ate` → 422; período sem movimentações → lista vazia com saldo inicial igual ao final.
- Conciliação com data igual ou anterior à última conciliação da conta → 422 (P3).
- Movimentação registrada com atraso (no dia seguinte, por exemplo): vale a **data da movimentação**, não a data em que foi lançada. Se essa data for igual ou anterior à última conciliação, ela já está dentro do saldo real conciliado e não altera o saldo atual (RN06, P2).
- Saldo calculado negativo → aceito sem aviso (RN11). O alerta de "vai ficar negativo" é da fase 7.
- Até a fase 4, toda conciliação de conta de giro vai mostrar divergência grande, porque gastos e ganhos ainda não são lançados. É esperado e fica registrado (ver Riscos).

## 8. Fora do Escopo

- **Saldo projetado**, alerta de saldo negativo e evento `ConciliacaoFeita`: fase 7.
- **`GET /api/contas/conciliacao-reserva`** e a distribuição do rendimento entre as categorias de reserva: dependem do módulo `reserva` (fase 8). Nesta fase o rendimento só credita a conta.
- **Endpoints de entrada e saída avulsas.** Ganhos e gastos entram pelo `fluxo` (fase 4); até lá, a diferença aparece na conciliação.
- **Pagamento de fatura de ponta a ponta**: depende de `cartoes` (fase 5). Só a fachada fica pronta.
- **Schema `auditoria` e trigger de auditoria** nas tabelas de movimentação: decisão nova deste PRD (ver Histórico). O sistema é de usuário único; a exclusão de movimentação é lógica e reversível (RF13), o que cobre o caso de apagar por engano.
- **Importação de extrato em PDF** (pedido do usuário, v1.6): receber o PDF do extrato da conta e armazenar os valores. Fica para um PRD próprio, depois do `fluxo` (fase 4), porque as linhas do extrato viram gastos e ganhos, e porque cada banco tem um layout de PDF diferente. O ponto de ligação com este módulo já existe: o saldo final do extrato pode alimentar uma conciliação (RF20).
- **Resgate programado de aplicações** (renda fixa como Tesouro e CDB, ou aplicação com data de resgate): feature futura com PRD próprio, pedida pelo usuário (v1.8). Nesta fase, a movimentação com data futura já é aceita (RN15).
- Importação de extrato em OFX ou CSV e integração com bancos (Open Finance).
- Regras específicas de `BENEFICIO` (ex.: não pagar fatura com VR): entram com `cartoes` e `fluxo`.
- Edição de movimentação: a correção é excluir e lançar de novo.

## 9. Dependências e Riscos

| Tipo | Descrição | Impacto |
|--------------|--------------------------------------------------------|----------|
| Dependência | Kernel `comum` (`Dinheiro`, `Competencia`) e `auth` (token) das fases 1–2 | Alto |
| Risco | Sem `fluxo`, as conciliações das contas de giro mostram divergências grandes até a fase 4, poluindo o histórico de divergências desse período | Médio |
| Risco | Movimentação excluída por engano. Mitigação: a exclusão é lógica e reversível (RF13, RF15) | Baixo |
| Risco | A doc `00-arquitetura.md` ainda prevê o trigger de auditoria nas tabelas financeiras; precisa ser atualizada para refletir a decisão nova | Baixo |
| Risco | IDs gerados pelo servidor impedem reenvio seguro pela fila offline do app (ver P1) | Médio |

## 10. Critérios de Aceitação

- [x] Conta criada com saldo inicial tem saldo atual igual ao inicial e uma conciliação com divergência zero (RF01)
- [x] Transferência debita a origem, credita o destino e não altera a posição geral (RF10, RN02)
- [x] Transferência para a mesma conta é rejeitada pela aplicação e pelo banco (RN04)
- [x] Saída com conta de destino é rejeitada pelo banco (RN04)
- [x] Rendimento credita a conta (RF11)
- [x] O saldo atual parte da última conciliação mais as movimentações posteriores (RN06)
- [x] A conciliação registra a divergência e vira a nova base (RF20)
- [x] Excluir ou lançar movimentação retroativa não altera a divergência já registrada (RN07)
- [x] Movimentação excluída sai do saldo e do extrato, continua no banco e volta a contar ao ser restaurada (RF13, RF15)
- [x] `UPDATE` e `DELETE` em conciliação são rejeitados pelo banco (RN07)
- [x] O extrato mostra as movimentações do período com saldo inicial e final corretos (RF12)
- [x] O consolidado devolve `posicaoGeral` (todas as contas ativas − dívidas, hoje zero) e `saldoEmConta` (só saldos positivos de contas correntes), sem categoria de reserva (RF06, RN01, RN12)
- [x] Conta com saldo negativo aceita novas movimentações e entra negativa nas somas (RN11)
- [x] Conta corrente negativa mostra o cheque especial em uso e o limite disponível; o uso não é descontado duas vezes da posição geral (RN11)
- [x] Conta corrente negativa além do limite é aceita e marcada com `acimaDoLimite: true` (P8)
- [x] Limite de cheque especial em conta que não é `CORRENTE` é rejeitado com 422 (RF01, RF04)
- [x] Conta inativa não aceita movimentação nem conciliação (RN08)
- [x] A fachada registra entrada, saída e pagamento de fatura, e `contasDeReserva()` devolve só contas `RESERVA` ativas (RF30)
- [x] Nenhum outro módulo acessa a parte interna de `contas` (RF31, teste do Spring Modulith)
- [x] Todos os endpoints exigem token (SEC01)
- [x] Transferência com data futura é aceita e conta no saldo atual (RN15)
- [x] Simulação excluída some da listagem e da comparação, continua no banco e volta ao ser restaurada; âncora com estimativas vivas continua protegida (RF40, RF41)
- [x] Tabelas de `auth` usam `created_at` e `updated_at`, sem mudar a API; revogação de dispositivo continua definitiva (RF42, RF43)
- [x] Gate de cobertura de 90% em linhas e ramos continua passando (RNF05)

## 11. Decisões sobre as pendências

Todas resolvidas na aprovação (v1.5).

| # | Pendência | Recomendação | Motivo |
|---|---|---|---|
| P1 | Reenvio pela fila offline do app (transferência e rendimento criados sem sinal) | **Aprovada (v1.3):** ID gerado pelo cliente (UUIDv7) com `PUT /api/contas/movimentacoes/{id}`, igual ao histórico da calculadora: mesmo ID e mesmo corpo → 200 com o existente; corpo diferente → 409 | Com `POST` e ID do servidor, um reenvio por rede ruim lança a transferência duas vezes |
| P2 | Movimentação no mesmo dia da conciliação: está dentro ou fora do saldo real? | **Aprovada (v1.4):** o saldo real vale para o **fim do dia** da conciliação; só movimentações com data **posterior** entram no saldo atual. Lançamentos podem ser feitos na hora ou no dia seguinte: o que conta é a data da movimentação | Concilie com o saldo do fim do dia; lançar com atraso não distorce o saldo |
| P3 | Conciliação com data anterior à última, ou duas no mesmo dia | **Aprovada (v1.5):** rejeitar com 422: a data precisa ser posterior à da última conciliação da conta | Mantém uma linha do tempo simples; uma conciliação errada se corrige com outra no dia seguinte |
| P4 | ~~Transferência que deixa a origem negativa~~ | **Resolvida (v1.3):** aceita; em conta corrente, o negativo é uso do cheque especial (RN11) | Saldo negativo é comum e não deve ser bloqueado |
| P5 | Desativar conta com saldo diferente de zero | **Aprovada (v1.4):** aceitar sem bloquear; a conta sai do consolidado | As contas no sistema são registro do usuário; desativar é decisão dele |
| P6 | O que conta como "dívida de crédito" na posição geral (a partir da fase 5) | **Resolvida em parte (v1.4):** dívida de crédito é **qualquer compra feita no cartão de crédito ainda não paga**, pelo valor total da compra (desconta da posição geral de uma vez); as parcelas são outra leitura. O detalhamento fica para o PRD de `cartoes` (fase 5). Nesta fase só existe a interface `FontesDeDivida` (RF32) | O módulo `cartoes` vai definir valor total × parcelas; aqui basta o ponto de ligação |
| P7 | Quais contas entram no `saldoEmConta` | **Aprovada (v1.4):** soma dos saldos **positivos** das contas `CORRENTE` ativas | Definição do usuário; o negativo da corrente é cheque especial |
| P8 | Conta corrente com saldo negativo **além** do limite do cheque especial | **Aprovada (v1.5):** aceitar e sinalizar `acimaDoLimite: true` na resposta do saldo e do consolidado, sem bloquear | Pode acontecer de verdade (o banco cobra, ou o limite cadastrado está desatualizado); bloquear impediria registrar o fato |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|--------|------------|----------------------------|
| 1.0 | 2026-11-14 | Criação inicial. Escopo: fase 3 (`contas` parte 1). Decisões novas em relação à doc 06 e à doc 00, tomadas na entrevista: (a) sem endpoints de entrada e saída avulsas até a fase 4; (b) saldo inicial obrigatório na criação, gravado como primeira conciliação; (c) exclusão definitiva de movimentação; (d) **sem schema `auditoria` nem trigger de auditoria** (usuário único). Mantido o trigger de imutabilidade de `contas.conciliacoes`, que reaproveita a função da V001 |
| 1.1 | 2026-11-14 | Pedido do usuário: (a) **sem limite de cheque especial**, saldo negativo é normal (RN11, P4 resolvida); (b) consolidado com **`posicaoGeral`** (contas − dívidas de crédito) e **`saldoEmConta`** (contas do dia a dia), RF06, RN12; (c) interface `FontesDeDivida` (RF32); (d) novas pendências P6 e P7 |
| 1.2 | 2026-11-14 | Esclarecimento do usuário: a posição geral é a **variável global do dinheiro** e a visão central do módulo; saldo por conta é detalhe (objetivo 4, RF06, RN12, nova RN13) |
| 1.3 | 2026-11-14 | P1 aprovada. **Cheque especial de volta**, só em conta `CORRENTE`: saldo negativo nela é uso do limite (`chequeEspecialEmUso`, `limiteDisponivel`), sem contar duas vezes na posição geral (RF01, RF04, RF05, RN11). Nova pendência P8 |
| 1.4 | 2026-11-14 | P2, P5 e P7 aprovadas; P6 resolvida em parte (dívida de crédito = valor total das compras no cartão ainda não pagas, detalhes no PRD de `cartoes`). `saldoEmConta` passa a ser a soma dos saldos positivos das contas correntes (RF06, RN13). Movimentação lançada com atraso vale pela data da movimentação |
| 1.5 | 2026-11-14 | P3 e P8 aprovadas como recomendado. Status aprovado |
| 1.6 | 2026-11-14 | Registrada em Fora do Escopo a importação de extrato em PDF, pedida pelo usuário, para um PRD próprio depois da fase 4 |
| 1.7 | 2026-11-14 | Pedido do usuário: exclusão de movimentação passa a ser **lógica e reversível** (RF13, novo RF15); padrão de colunas `created_at`, `updated_at` e `deleted_at` nas tabelas em que fazem sentido (RN14) |
| 1.8 | 2026-11-14 | Decisões do usuário: movimentação com **data futura aceita** e contando no saldo atual (RN15); resgate programado de aplicações registrado como feature futura; **padrão de datas aplicado às tabelas das fases 1–2** (RF40–RF43), com exclusão lógica e restauração de simulação |
