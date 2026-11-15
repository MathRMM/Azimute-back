# SPEC — Azimute Core: Módulo `fluxo`, parte 1, com holerite (Fase 4 do roadmap)

**PRD de referência:** `.planning/prd_fluxo.md` (v1.3, aprovado)
**Data:** 2026-11-14
**Status:** aprovado
**Autor:** Claude Code

> Nomenclatura: a "Fase 4" do título é a do roadmap geral (`00-arquitetura.md`). As **Fases 1 a 7** deste documento são as etapas de implementação deste SPEC.

---

## 1. Visão Geral Técnica

O módulo `fluxo` entra no monólito modular existente, no pacote `br.dev.azimute.fluxo`, que já existe vazio e já é verificado pelo `ModularidadeTest`. O holerite fica num subpacote interno, `br.dev.azimute.fluxo.folha`: para o Spring Modulith, subpacote é parte interna do mesmo módulo.

**Mesmos padrões das fases anteriores:**
- **Persistência:** `JdbcClient` com SQL explícito; schema só pelo Flyway.
- **Erros:** subclasses de `ErroDeNegocio`, traduzidas pelo `TradutorDeErros` (422, 404, 409).
- **Dinheiro na API:** string decimal; na entrada, `BigDecimal` com `@Digits(fraction = 2)` (desvio (b) da Fase 2 de contas).
- **Idempotência:** `PUT` com ID do cliente, `ON CONFLICT DO NOTHING` e comparação de conteúdo; reenvio idêntico → 200, diferente → 409, ID excluído → 409 (T9 de contas).
- **Datas de controle:** `created_at`, `updated_at` (pelo SQL da aplicação) e `deleted_at` onde há exclusão lógica (RNF08).
- **Segurança:** tudo sob `/api/**`, já protegido por token (SEC01).

**Fronteira do módulo.** Públicos, no pacote base: `FluxoFacade`, `ResumoMes`, `RegistrarGasto`, `GastoId`, `GanhoId`, `CategoriaId`, `GastoLancado`, `GanhoRegistrado`. O resto é package-private. Um teste no `ModularidadeTest` fixa essa lista (como o de `contas`).

**Ligação com `contas`.** Todo efeito de dinheiro em conta passa pela `ContasFacade`, na mesma transação do registro em `fluxo` (RN04). `contas` não conhece os tipos de `fluxo`: recebe um `UUID` de referência (T1 de contas). Cada efeito tem a sua referência:

| Efeito | Movimentação em `contas` | Referência (`UUID`) |
|---|---|---|
| Gasto à vista | `SAIDA` | ID do gasto |
| Recebimento de ganho (avulso, adiantamento, líquido do holerite) | `ENTRADA` | ID do recebimento |
| FGTS do mês, previdência básica, contrapartida, voluntária | `ENTRADA` | ID do lançamento de folha |
| Gasto em folha (vale-transporte) | nenhuma: o dinheiro nem chegou à conta | — |

**Forma de pagamento como tipo selado (RNF02).** `FormaPagamento` é uma `sealed interface` com `AVista(ContaId)` e `DescontoEmFolha(HoleriteId)`. A fase 5 do roadmap acrescenta `Credito`, e todo `switch` sem `default` deixa de compilar até tratar o caso novo.

**Holerite: um processamento, duas entradas.** O leitor do PDF (Fase 6) e o lançamento manual em JSON (Fase 5) produzem o mesmo objeto, `HoleriteLido` (seção 6.3). Todo o processamento (conferência, catálogo, contrapartida, lançamentos) parte dele. Por isso a Fase 5 testa toda a regra antes de existir o leitor.

**Efeitos do holerite são reaplicáveis.** Os lançamentos que um holerite gera (recebimentos, FGTS, previdência, gastos em folha) têm referências **determinísticas**, derivadas do ID do holerite e do papel do lançamento (T6). Reaplicar os efeitos (reclassificação de rubrica, restauração) compara o que deveria existir com o que existe e só altera a diferença, sem duplicar.

### Decisões técnicas deste SPEC

| # | Decisão | Motivo |
|---|---|---|
| T1 | O holerite fica em `fluxo.folha`, não num módulo novo | O holerite produz ganhos e gastos, que são de `fluxo`; um módulo `folha` dependeria de `fluxo` e `fluxo` dele, por causa do resumo do mês |
| T2 | A fachada de `contas` ganha `alterarLancamento`, `excluirLancamento`, `restaurarLancamento` (por referência) e `buscarConta`. A alteração atualiza a movimentação no lugar (`updated_at`), não exclui e recria | RF42; a movimentação mantém o ID, e o histórico de divergências não muda (RN07 de contas). `buscarConta` serve para validar o tipo das contas da configuração da folha |
| T3 | A coluna `contas.movimentacoes.ganho_id` passa a guardar a referência de qualquer **entrada** vinda de outro módulo (recebimento ou lançamento de folha). Sem renomear | O índice único parcial já impede duas entradas com a mesma referência; renomear exigiria migration e mudança de código sem ganho |
| T4 | Calendário de dias úteis no `comum` (`DiasUteis`): fim de semana e feriados bancários nacionais. Fixos: 1/1, 21/4, 1/5, 7/9, 12/10, 2/11, 15/11, 20/11, 25/12. Móveis, a partir da Páscoa: Carnaval (segunda e terça), Sexta-feira Santa e Corpus Christi | P5. São os dias sem expediente bancário em todo o país; `cartoes` reaproveita na fase 5 do roadmap |
| T5 | Ganho avulso tem um recebimento só, com o **mesmo ID do ganho** | O `PUT` avulso continua com um ID; o recebimento herda o ID do cliente, sem gerar outro |
| T6 | Referências dos efeitos do holerite: `UUID` por nome (`nameUUIDFromBytes`) de `holeriteId + papel` (ex.: `"…:FGTS"`, `"…:PREV_BASICA"`, `"…:GASTO:/507"`) | Reaplicar ou restaurar encontra o mesmo lançamento; nunca duplica |
| T7 | Contrapartida da previdência gravada numa coluna do holerite, calculada na importação pela regra com a maior `a_partir_de ≤ competência`. Regras são só inseridas, nunca alteradas | RN19: regra nova não muda o passado; o histórico de regras é a trilha da mudança |
| T8 | O PDF fica numa tabela à parte (`fluxo.holerite_arquivos`, `BYTEA`), ligada 1:1 ao holerite, com o SHA-256 | A listagem de holerites não carrega o arquivo; o hash detecta o reenvio (RN11) |
| T9 | Holerite único por competência entre os vivos: índice único parcial em `competencia WHERE deleted_at IS NULL`. Hash igual a um holerite vivo → 200; competência ocupada por outro hash → 409 `HOLERITE_JA_IMPORTADO`; hash de um holerite excluído → 409 `HOLERITE_EXCLUIDO` (use o `restaurar`) | RN11 e o mesmo comportamento do T9 de contas |
| T10 | Impostos e totais do resumo da folha são calculados na leitura, das rubricas e do catálogo; só os efeitos em conta e os gastos em folha são gravados | Reclassificar uma rubrica (RN13) muda os totais sem reprocessar nada além dos lançamentos |
| T11 | Rubrica "a classificar" é uma flag do catálogo (`a_classificar`), com a natureza provisória `PROVENTO` ou `OUTRO_DESCONTO` pela coluna em que apareceu | RF36: a importação não para; o aviso sai na resposta |
| T12 | Upload com limite de 2 MB no `spring.servlet.multipart`; `MaxUploadSizeExceededException` → 413 `ARQUIVO_GRANDE_DEMAIS` no `TradutorDeErros`. Assinatura `%PDF-` conferida nos primeiros bytes; PDF cifrado ou com mais de 2 páginas → 422 | SEC04, SEC06 |
| T13 | Eventos publicados pelo `ApplicationEventPublisher` dentro da transação; o Spring Modulith entrega depois do commit (registro de publicações já ativo, tabela da V001) | RF52; mesmo mecanismo previsto na doc 00 |
| T14 | `GET /api/fluxo/resumo`: competência do gasto pela `data` (RN09); recebido = Σ líquido dos ganhos **da competência**; a média de cada categoria usa as 3 competências anteriores, com zero onde não houve gasto (RN17) | RF50 |
| T15 | Gasto em folha: `data` = data do líquido do holerite; categoria vem do catálogo da rubrica | RF33 (d), RN12 |

## 2. Tecnologias e Versões

| Tecnologia | Versão | Uso nesta alteração |
|---|---|---|
| Java | 25 | `sealed interface` da forma de pagamento; `switch` exaustivo |
| Spring Boot | 4.1.2 | Web MVC (inclusive `multipart`), JdbcClient, Validation, Flyway |
| Spring Modulith | 2.1.1 | Fronteira de `fluxo`; eventos depois do commit |
| PostgreSQL | 18 | Schema `fluxo`, constraints, índices parciais, `BYTEA` |
| **Apache PDFBox** | **3.0.8** (nova) | Extração de texto do holerite (RNF03), só na Fase 6 |
| Testcontainers, JUnit 5, AssertJ, Mockito | gerenciados pelo Boot | Testes |
| JaCoCo | plugin do Gradle | Gate de 90% (RNF07) |

## 3. Mapa de Arquivos

```
backend-azimute-core/
├── [REF] .planning/prd_fluxo.md
├── [REF] assistente-docs/03-modulo-fluxo.md
├── [MOD] .planning/spec_fluxo.md                          — checklists marcados a cada fase
├── [MOD] build.gradle.kts                                 — PDFBox (Fase 6)
├── [MOD] Makefile                                         — alvos de gasto, ganho, holerite e resumos
├── [MOD] src/main/resources/application.yml               — limite de upload (Fase 6)
├── src/main/resources/db/migration/
│   ├── [NEW] V006__contas_bloqueado.sql                   — tipos FGTS e PREVIDENCIA (Fase 1)
│   └── [NEW] V007__fluxo.sql                              — schema fluxo, categorias pré-definidas, catálogo, regra de contrapartida (Fase 2)
├── src/main/java/br/dev/azimute/comum/
│   └── [NEW] DiasUteis.java                               — dia útil e dia útil anterior, feriados nacionais (T4)
├── src/main/java/br/dev/azimute/contas/
│   ├── [MOD] TipoConta.java                               — FGTS, PREVIDENCIA
│   ├── [MOD] ConsolidadoService.java, ContasController    — campo bloqueado (RF41)
│   ├── [MOD] ContasFacade.java, ContasFacadeImpl.java     — alterar/excluir/restaurar lançamento, buscarConta (T2)
│   └── [MOD] MovimentacaoRepository.java, MovimentacaoService.java — operações por referência
├── [MOD] src/main/java/br/dev/azimute/plataforma/web/TradutorDeErros.java — 413 no upload (Fase 6)
├── src/main/java/br/dev/azimute/fluxo/
│   ├── [MOD] package-info.java
│   ├── [NEW] FluxoFacade.java, ResumoMes.java, RegistrarGasto.java          — públicos
│   ├── [NEW] GastoId.java, GanhoId.java, CategoriaId.java                     — públicos
│   ├── [NEW] GastoLancado.java, GanhoRegistrado.java                          — públicos: eventos
│   ├── [NEW] FormaPagamento.java                                              — sealed: AVista, DescontoEmFolha
│   ├── [NEW] Categoria.java, Gasto.java, Ganho.java, Recebimento.java, TipoGanho.java
│   ├── [NEW] CategoriaRepository, GastoRepository, GanhoRepository            — SQL
│   ├── [NEW] CategoriaService, GastoService, GanhoService, ResumoService
│   ├── [NEW] FluxoFacadeImpl.java
│   ├── [NEW] CategoriasController, GastosController, GanhosController, ResumoController
│   ├── [NEW] *Request.java / *Response.java / *Exception.java
│   └── folha/
│       ├── [NEW] HoleriteLido.java, RubricaLida.java, BasesLidas.java          — contrato comum do PDF e do manual
│       ├── [NEW] Holerite.java, HoleriteId.java, NaturezaRubrica.java, RubricaCatalogo.java, RegraContrapartida.java
│       ├── [NEW] ConferenciaDeHolerite.java                                    — RN10, função pura
│       ├── [NEW] EfeitosDoHolerite.java                                        — o que um holerite deve gerar (função pura, T6)
│       ├── [NEW] DatasDoSalario.java                                           — RN18 sobre DiasUteis
│       ├── [NEW] LeitorDeHolerite.java                                         — interface por layout (RNF04)
│       ├── [NEW] LeitorDemonstrativoPagamento.java                             — layout atual, PDFBox (Fase 6)
│       ├── [NEW] HoleriteRepository, CatalogoRepository, ConfiguracaoFolhaRepository, AdiantamentoRepository
│       ├── [NEW] HoleriteService, CatalogoService, ConfiguracaoFolhaService, ResumoFolhaService
│       └── [NEW] FolhaController.java
└── src/test/java/br/dev/azimute/
    ├── [MOD] ModularidadeTest.java                        — API pública de fluxo
    ├── comum/[NEW] DiasUteisTest.java
    ├── contas/[NEW] ContasFacadeLancamentosIT.java, [MOD] ConsolidadoServiceTest, ConsolidadoIT
    └── fluxo/
        ├── [NEW] MontadorDeFluxo.java                     — dados de teste
        ├── [NEW] SchemaFluxoIT.java, FormaPagamentoTest.java
        ├── [NEW] CategoriasIT.java, GastoServiceTest.java, GastosIT.java
        ├── [NEW] GanhoServiceTest.java, GanhosIT.java
        ├── [NEW] ResumoServiceTest.java, ResumoIT.java, FluxoFacadeIT.java
        └── folha/
            ├── [NEW] HoleriteSintetico.java               — gera o PDF de teste com dados fictícios (SEC03)
            ├── [NEW] ConferenciaDeHoleriteTest.java, EfeitosDoHoleriteTest.java, DatasDoSalarioTest.java
            ├── [NEW] HoleriteServiceTest.java, HoleriteManualIT.java, AdiantamentoIT.java, CatalogoIT.java
            ├── [NEW] LeitorDemonstrativoPagamentoTest.java, HoleritePdfIT.java
            └── [NEW] ResumoFolhaIT.java
```

---

## 4. Fases de Implementação

**Git:** branch pai `feature/fluxo`, criada a partir da `main` (que já tem fundação, contas e Swagger). Uma branch filha por fase (`feature/fluxo-fase-N`), com merge `--no-ff` na pai ao fim de cada uma. PRD e SPEC entram por uma branch filha própria (`feature/fluxo-planejamento`), não direto na pai. Correção depois de validar: branch filha `feature/fluxo-fix-…`. PR draft por fase quando o `gh` estiver autenticado.

**Makefile:** todo alvo novo é testado rodando o próprio `make` com entrada por pipe, não só `make -n` ou `curl`.

---

### Fase 1 — Base em `contas` e `comum`

**Objetivo:** `contas` aceita contas de FGTS e previdência, separa o patrimônio bloqueado na visão central e deixa o `fluxo` alterar, excluir e restaurar o que lançou; o `comum` sabe o que é dia útil.

**Arquivos desta fase:** `V006__contas_bloqueado.sql`; `TipoConta`, `ConsolidadoService`, `ContasController`, `ContasFacade`, `ContasFacadeImpl`, `MovimentacaoRepository`, `MovimentacaoService`; `DiasUteis`; `DiasUteisTest`, `ContasFacadeLancamentosIT`, `ConsolidadoServiceTest`, `ConsolidadoIT`, `SchemaContasIT`.

**O que fazer:**
- `V006`: troca o `CHECK` de `tipo` de `contas.contas` para incluir `FGTS` e `PREVIDENCIA` (RF40). Sem cheque especial nesses tipos (a constraint T7 de contas já cobre).
- Consolidado (RF41, RN16): `posicaoGeral` e `saldoEmConta` deixam de somar `FGTS` e `PREVIDENCIA`; novo campo `bloqueado` com a soma dessas contas ativas. A lista de contas da resposta continua com todas.
- Fachada (RF42, T2, T3): `alterarLancamento(ref, conta, valor, data)`, `excluirLancamento(ref)`, `restaurarLancamento(ref)` para `ENTRADA` e `SAIDA` com aquela referência; `buscarConta(id)`. Conta nova inativa ou inexistente → mesmos erros da fachada atual (RN08 de contas). Excluir referência inexistente ou já excluída → sem erro (T3 de contas).
- `DiasUteis` (T4): `ehDiaUtil(data)` e `diaUtilAnteriorOuMesmo(data)`, com a Páscoa calculada pelo algoritmo de Meeus/Jones/Butcher. Sem Spring, como todo o `comum`.

**Testes (TDD):**
- [x] Banco → conta `FGTS` e `PREVIDENCIA` aceitas; tipo desconhecido recusado; limite de cheque especial em `FGTS` recusado
- [x] Consolidado → conta FGTS com R$ 1.000 e corrente com R$ 500: `posicaoGeral` 500, `saldoEmConta` 500, `bloqueado` 1.000
- [x] Consolidado → conta previdência inativa não entra em `bloqueado`
- [x] Fachada → alterar a saída de um gasto muda valor, data e conta, mantendo o ID da movimentação; o saldo das duas contas acompanha
- [x] Fachada → alterar para conta inativa → 422; inexistente → 404
- [x] Fachada → excluir e restaurar por referência; excluir de novo não dá erro; restaurar referência que não existe → 404
- [x] Fachada → alterar ou excluir uma `TRANSFERENCIA` pela referência é impossível (só `ENTRADA` e `SAIDA` têm referência de outro módulo)
- [x] `DiasUteis` → sábado, domingo, 25/12, 20/11 não são úteis; Carnaval, Sexta-feira Santa e Corpus Christi de 2026 e 2027 calculados certos; 31/10/2026 (sábado) → 30/10
- [x] Caso de borda: `diaUtilAnteriorOuMesmo` atravessando feriado colado em fim de semana (ex.: sexta-feira santa + fim de semana)

**Checklist de validação:**
- [x] RF40, RF41, RF42, RN16 e a base da RN18 (T4) atendidos
- [x] `api_publica_de_contas…` do `ModularidadeTest` continua com os seis tipos (a fachada só ganhou métodos)
- [x] Todos os testes desta fase passando; nenhum teste anterior quebrado; gate de 90% passando

**Critério de avanço:** checklist completo → Fase 2.

---

### Fase 2 — Banco e modelo do `fluxo`

**Objetivo:** o schema `fluxo` existe e recusa dado incoerente; o modelo compila com o `switch` exaustivo da forma de pagamento.

**Arquivos desta fase:** `V007__fluxo.sql`; `package-info`; IDs, `FormaPagamento`, `Categoria`, `Gasto`, `Ganho`, `Recebimento`, `TipoGanho`; em `folha`: `HoleriteLido`, `RubricaLida`, `BasesLidas`, `Holerite`, `HoleriteId`, `NaturezaRubrica`, `RubricaCatalogo`, `RegraContrapartida`; `SchemaFluxoIT`, `FormaPagamentoTest`, `MontadorDeFluxo`.

**O que fazer:**
- `V007` com as tabelas da seção 6.1 (RNF01, RNF08):
  - categorias com as nove pré-definidas (RF01) e índice único de nome sem maiúsculas e espaços (RN06);
  - gastos com `forma_coerente` (à vista exige conta; em folha exige holerite e não tem conta) e `valor > 0` (RN03);
  - ganhos com `liquido <= bruto` (RN02) e competência no dia 1;
  - recebimentos (avulso, adiantamento, líquido), lançamentos de folha, holerites, arquivos, rubricas do holerite, catálogo (com as seis rubricas do exemplo, RF35), regras de contrapartida (com 100% a partir de 2000-01, RF35a) e configuração da folha (linha única).
- Modelo: `FormaPagamento` selada (RNF02); `Ganho` recusa líquido maior que bruto (RN02); `TipoGanho` com os cinco tipos (RF21).

**Testes (TDD):**
- [x] Banco → gasto à vista sem conta, ou com holerite → violação de `forma_coerente`; gasto em folha com conta → violação
- [x] Banco → gasto com valor zero ou negativo → violação
- [x] Banco → ganho com líquido maior que o bruto → violação
- [x] Banco → duas categorias "Lazer" e " lazer " → violação do índice único
- [x] Banco → dois holerites vivos na mesma competência → violação; com o primeiro excluído → aceito (T9)
- [x] Banco → nove categorias pré-definidas, seis rubricas no catálogo e uma regra de contrapartida de 100% existem depois da migration
- [x] Banco → `created_at`/`updated_at` preenchidos; `deleted_at` nulo (RNF08)
- [x] Modelo → `switch` sobre `FormaPagamento` cobre as duas formas sem `default`
- [x] Modelo → ganho com líquido maior que bruto → exceção de domínio
- [x] Modularidade → `ModularidadeTest` continua passando

**Checklist de validação:**
- [x] RF01 (dados), RF14, RF21, RN02, RN03, RN06 (banco), RNF01, RNF02, RNF08 atendidos
- [x] Testes desta fase e das anteriores passando; gate de 90% passando

**Critério de avanço:** checklist completo → Fase 3.

---

### Fase 3 — Categorias e gastos à vista

**Objetivo:** o usuário mantém as categorias e registra, corrige e consulta gastos à vista, com o reflexo na conta.

**Arquivos desta fase:** `CategoriaRepository`, `CategoriaService`, `CategoriasController`; `GastoRepository`, `GastoService`, `GastosController`; `GastoLancado`; DTOs e erros; `CategoriasIT`, `GastoServiceTest`, `GastosIT`.

**O que fazer:**
- Categorias (RF02–RF04, RN05, RN06): criar, listar (ativas por padrão, filtro para inativas), renomear, desativar e reativar. Nome repetido → 409.
- Gastos à vista (RF10–RF13, RN01, RN04, RN07, RN08):
  - `PUT /api/gastos/{id}`: grava o gasto e chama `registrarSaida` na mesma transação; reenvio idêntico → 200; diferente → 409; ID excluído → 409.
  - `PATCH`: altera e chama `alterarLancamento`.
  - `DELETE` / `restaurar`: exclusão lógica do gasto e `excluirLancamento` / `restaurarLancamento`.
  - `GET ?competencia=&categoria=`: por data da competência, mais recente primeiro.
- Categoria inativa no registro ou na edição → 422; inexistente → 404. Conta: erros vindos da fachada.
- Gasto em folha pela API: `PATCH` só aceita `categoria` (RF15, RN15); `DELETE` → 422 `GASTO_DO_HOLERITE`.
- Publica `GastoLancado` no registro (RF52, T13).

**Testes (TDD):**
- [x] Serviço → registrar gasto chama a saída com o ID do gasto, valor e data; falha na fachada → nada gravado (RN04)
- [x] Serviço → reenvio idêntico não chama a fachada de novo; corpo diferente → 409
- [x] Serviço → editar valor, data e conta chama `alterarLancamento` com os novos valores
- [x] Serviço → gasto em folha: editar valor → 422; editar categoria → aceito
- [x] API → `PUT` cria (201) e o saldo da conta cai; reenvio → 200; `DELETE` → 204 e o saldo volta; `restaurar` → 200 e o saldo cai de novo
- [x] API → categoria criada inline é usada no gasto seguinte; renomear muda o nome na listagem de gastos; desativada some do `GET /api/categorias` e recusa gasto novo
- [x] API → valor com 3 casas, zero ou negativo → 422; sem token → 401
- [x] Evento → `GastoLancado` publicado depois do commit, e não publicado quando a transação falha
- [x] Caso de borda: gasto com data futura aceito (RN08); gasto em conta inativa → 422

**Checklist de validação:**
- [x] RF02–RF04, RF10–RF13, RF15 (API de gasto), RN01, RN04–RN09, RF52 (`GastoLancado`) atendidos
- [x] Alvo `make gasto` testado rodando o `make`
- [x] Testes desta fase e das anteriores passando; gate de 90% passando

**Critério de avanço:** checklist completo → Fase 4.

---

### Fase 4 — Ganhos avulsos

**Objetivo:** o usuário registra e corrige ganhos que não vêm do holerite, com a entrada na conta.

**Arquivos desta fase:** `GanhoRepository`, `GanhoService`, `GanhosController`; `GanhoRegistrado`; DTOs e erros; `GanhoServiceTest`, `GanhosIT`.

**O que fazer:**
- `PUT /api/ganhos/{id}` (RF20): ganho com um recebimento de mesmo ID (T5), `registrarEntrada` na mesma transação; mesmas regras de reenvio do gasto. Tipo `SALARIO` → 422 `SALARIO_SO_PELO_HOLERITE` (P4).
- `PATCH`, `DELETE`, `restaurar` (RF22) com reflexo na entrada; ganho de holerite → 422 `GANHO_DO_HOLERITE` (RN15).
- `GET ?competencia=` (RF23) com os recebimentos de cada ganho (RF24), inclusive os do holerite quando existirem (Fase 5).
- Publica `GanhoRegistrado` (RF52).

**Testes (TDD):**
- [x] Serviço → registrar ganho chama a entrada com a referência do recebimento; líquido maior que bruto → 422 (RN02)
- [x] Serviço → tipo `SALARIO` → 422; reenvio idêntico → 200; diferente → 409
- [x] API → `PUT` cria e o saldo da conta sobe; editar o líquido ajusta a entrada; excluir e restaurar refletem no saldo
- [x] API → listagem da competência traz bruto, líquido, tipo e recebimentos
- [x] Evento → `GanhoRegistrado` publicado depois do commit
- [x] Caso de borda: competência diferente do mês do recebimento (salário de outubro pago em novembro) é aceita (RN09)

**Checklist de validação:**
- [x] RF20–RF24, RN02, RN04, RN09, RF52 (`GanhoRegistrado`), P4 atendidos
- [x] Alvo `make ganho` testado rodando o `make`
- [x] Testes desta fase e das anteriores passando; gate de 90% passando

**Critério de avanço:** checklist completo → Fase 5.

---

### Fase 5 — Processamento do holerite (entrada manual)

**Objetivo:** um holerite lançado em JSON gera, numa transação só, o ganho, os recebimentos, o FGTS, a previdência separada e os gastos em folha, com conferência, catálogo, contrapartida por vigência, adiantamento antecipado e exclusão reversível.

**Arquivos desta fase:** em `folha`: `ConferenciaDeHolerite`, `EfeitosDoHolerite`, `DatasDoSalario`, `HoleriteRepository`, `CatalogoRepository`, `ConfiguracaoFolhaRepository`, `AdiantamentoRepository`, `HoleriteService`, `CatalogoService`, `ConfiguracaoFolhaService`, `FolhaController`; DTOs e erros; `ConferenciaDeHoleriteTest`, `EfeitosDoHoleriteTest`, `DatasDoSalarioTest`, `HoleriteServiceTest`, `HoleriteManualIT`, `AdiantamentoIT`, `CatalogoIT`.

**O que fazer:**
- Configuração (RF30): `PUT /api/folha/configuracao`; a conta FGTS precisa ser do tipo `FGTS` e a de previdência do tipo `PREVIDENCIA` (pela `buscarConta`, T2) → senão 422. Importar sem configuração → 422 `FOLHA_NAO_CONFIGURADA`.
- `ConferenciaDeHolerite` (RN10): Σ vencimentos = total, Σ descontos = total, vencimentos − descontos = líquido; erro aponta qual conferência falhou.
- `DatasDoSalario` (RN18): adiantamento no dia 15 e líquido no último dia da competência, recuando para o dia útil anterior; datas informadas prevalecem.
- `EfeitosDoHolerite` (função pura, T6, RN12, RN14, RN19): dado o holerite, o catálogo, a contrapartida gravada e a configuração, devolve a lista de lançamentos esperados (recebimentos, FGTS, previdência básica, contrapartida, voluntária, gastos em folha), cada um com a referência determinística.
- `POST /api/folha/holerites/manual` (RF32, RF33): confere, calcula a contrapartida pela regra vigente (T7), grava holerite e rubricas, aplica os efeitos pela fachada, cria o ganho `SALARIO`; tudo numa transação (RN04).
- Reenvio (RN11, T9): neste caminho, o "hash" é o do JSON canônico do holerite.
- Adiantamento (RF34): `PUT /api/folha/adiantamentos/{id}` lança a entrada; a importação da mesma competência associa e, se o valor divergir, ajusta pela `alterarLancamento`.
- Catálogo (RF35, RF36, RN13, T11): listar, reclassificar; rubrica nova entra "a classificar" e a resposta avisa; reclassificar reaplica os efeitos dos holerites que usam a rubrica, sem mudar a contrapartida gravada.
- Contrapartida (RF35a, RF35b, RN19): listar e incluir regra com vigência; corrigir a contrapartida de um holerite ajusta só aquele lançamento.
- Exclusão e restauração (RF38): exclui o holerite e todos os efeitos; o adiantamento lançado antes volta a ser solto; restaurar reaplica.
- Gasto em folha (RF15): a categoria pode ser trocada pela API de gastos (Fase 3) e a reaplicação dos efeitos preserva a escolha.

**Testes (TDD):**
- [ ] Conferência → holerite do exemplo (valores fictícios do PRD) passa; vencimentos somando diferente do total → falha apontando "vencimentos"; líquido errado → falha apontando "líquido"
- [ ] Datas → competência 2026-10: adiantamento 15/10 (quinta), líquido 30/10 (31 é sábado); competência com dia 15 num domingo → sexta 13
- [ ] Efeitos → básica 50, voluntária 250, contrapartida 50 → três lançamentos de previdência (50, 50, 250); vale-transporte 300 → gasto em folha de 300 em Transporte; FGTS 400 → um lançamento
- [ ] Efeitos → mesmas entradas produzem as mesmas referências (T6)
- [ ] Serviço → regra de 100% até 2026-12 e de 50% a partir de 2027-01: holerite de 2026-12 grava contrapartida = básica; de 2027-01 grava a metade; incluir a regra nova não muda o de 2026-12 (RN19, T7)
- [ ] Serviço → corrigir a contrapartida de um holerite ajusta só a entrada daquele holerite (RF35b)
- [ ] API → importar o holerite manual cria o ganho com dois recebimentos, FGTS, previdência separada e o gasto em folha; os saldos das contas salário, FGTS e previdência e o `bloqueado` do consolidado batem (fluxo principal A do PRD)
- [ ] API → totais inconsistentes → 422 e nada gravado (RN10); sem configuração → 422
- [ ] API → reenvio idêntico → 200; outro holerite na mesma competência → 409; reenvio de um holerite excluído → 409
- [ ] API → adiantamento lançado antes é associado sem duplicar; com valor diferente, a entrada é ajustada ao valor do holerite (RF34)
- [ ] API → rubrica desconhecida entra "a classificar" e é avisada; reclassificar como `GASTO`/Saúde cria o gasto em folha; reclassificar de volta remove (RN13)
- [ ] API → excluir o holerite tira tudo dos saldos e solta o adiantamento antecipado; restaurar devolve tudo (RF38)
- [ ] API → ganho do holerite não pode ser editado pela API de ganhos (RN15)
- [ ] Caso de borda: holerite sem adiantamento → um recebimento só; mês sem IRRF → imposto = INSS
- [ ] Caso de borda: configuração apontando conta de tipo errado → 422

**Checklist de validação:**
- [ ] RF15, RF30, RF32–RF36, RF35a, RF35b, RF38, RN04, RN10–RN15, RN18, RN19, P1–P4 atendidos
- [ ] Alvo `make holerite-manual` (envia um JSON de arquivo) testado rodando o `make`
- [ ] Testes desta fase e das anteriores passando; gate de 90% passando

**Critério de avanço:** checklist completo → Fase 6.

---

### Fase 6 — Leitura do PDF

**Objetivo:** o usuário envia o PDF do holerite e o sistema lê, guarda e processa pelo mesmo caminho da Fase 5.

**Arquivos desta fase:** `build.gradle.kts` (PDFBox); `application.yml` (multipart); `TradutorDeErros` (413); em `folha`: `LeitorDeHolerite`, `LeitorDemonstrativoPagamento`, mudanças em `HoleriteRepository` (arquivo) e `FolhaController` (upload, listagem, PDF); `HoleriteSintetico`, `LeitorDemonstrativoPagamentoTest`, `HoleritePdfIT`.

**O que fazer:**
- `LeitorDeHolerite` (RNF04): interface que recebe os bytes e devolve `HoleriteLido` ou um erro de leitura com o campo que falhou.
- `LeitorDemonstrativoPagamento` (RNF03): PDFBox, texto por posição; reconhece o cabeçalho "D E M O N S T R A T I V O", a competência por extenso ("Outubro 2026"), as linhas de rubrica (código, descrição, referência, vencimento, desconto), os totais, o líquido e a linha de bases (inclusive "FGTS Mês"). Números no formato brasileiro (`4.514,00`).
- Upload `POST /api/folha/holerites` (RF31, SEC04, T12): `multipart` com o arquivo e as datas opcionais; confere a assinatura `%PDF-`, o limite de 2 MB, cifragem e número de páginas (SEC06); guarda o arquivo e o SHA-256 (T8); segue o processamento da Fase 5. Falha de leitura → 422 `HOLERITE_ILEGIVEL` com o campo, sem conteúdo (SEC02).
- `GET /api/folha/holerites?ano=`, `GET /{id}` e `GET /{id}/pdf` (RF37), com `Content-Type: application/pdf`.
- `HoleriteSintetico` (SEC03): gera, nos testes, um PDF com o mesmo layout e dados fictícios; nenhum PDF real no repositório.
- Garantir que nenhum log do leitor ou do upload contenha valores ou texto do PDF (SEC02).

**Testes (TDD):**
- [ ] Leitor → o holerite sintético é lido com competência, as seis rubricas, totais, líquido e FGTS do mês exatos
- [ ] Leitor → número `1.234.567,89` lido certo; rubrica sem referência; linha de rubrica vazia ignorada
- [ ] Leitor → PDF sem o cabeçalho do layout → erro "layout desconhecido"; sem a linha do líquido → erro apontando "líquido"
- [ ] Leitor → PDF de 3 páginas ou cifrado → recusado (SEC06)
- [ ] API → upload do sintético produz o mesmo resultado que o lançamento manual do mesmo conteúdo (RF31 = RF32)
- [ ] API → arquivo que não começa com `%PDF-` → 422; maior que 2 MB → 413 (SEC04, T12)
- [ ] API → mesmo PDF reenviado → 200; o PDF baixado pelo `GET /{id}/pdf` é idêntico ao enviado (RF37, T8)
- [ ] Log → importação e erro de leitura não escrevem valores nem o texto do PDF (SEC02, verificado com captura de log)
- [ ] Desempenho → importação do sintético abaixo de 2 s no teste de integração (RNF05)

**Checklist de validação:**
- [ ] RF31, RF37, RNF03, RNF04, RNF05, SEC02–SEC06 atendidos
- [ ] **Validação com o holerite real**, fora do repositório, numa stack isolada: os valores lidos batem com o PDF (feita pelo usuário ou com o PDF numa pasta fora do git)
- [ ] Alvo `make holerite` (upload do PDF por caminho) testado rodando o `make`
- [ ] Testes desta fase e das anteriores passando; gate de 90% passando

**Critério de avanço:** checklist completo → Fase 7.

---

### Fase 7 — Resumos e fachada

**Objetivo:** o usuário vê o resumo anual da folha e o resumo do mês; `sistema` e `agenda` têm a fachada pronta.

**Arquivos desta fase:** `ResumoFolhaService` e endpoint em `FolhaController`; `ResumoService`, `ResumoController`, `ResumoMes`, `RegistrarGasto`, `FluxoFacade`, `FluxoFacadeImpl`; `ModularidadeTest`; `ResumoFolhaIT`, `ResumoServiceTest`, `ResumoIT`, `FluxoFacadeIT`.

**O que fazer:**
- `GET /api/folha/resumo?ano=` (RF39, T10): por mês e no ano, bruto, INSS, IRRF, total de impostos, previdência básica, contrapartida, voluntária e total depositado, FGTS depositado e líquido recebido; mais o saldo atual das contas FGTS e previdência (acumulado).
- `GET /api/fluxo/resumo?competencia=` (RF50, RN17, T14): total gasto (à vista + em folha), por categoria com a média das 3 competências anteriores, recebido, sobra.
- `FluxoFacade` (RF51): `resumoDoMes` e `registrarGasto` (mesmo caminho do `PUT`).
- `ModularidadeTest`: fixa os oito tipos públicos de `fluxo`.

**Testes (TDD):**
- [ ] Resumo da folha → dois holerites no ano: totais por mês e do ano; previdência separada; impostos = INSS + IRRF; reclassificar uma rubrica de `OUTRO_DESCONTO` para `IRRF` muda o total de impostos (T10)
- [ ] Resumo do mês → categoria com gastos em jul, ago e nada em set: média = (jul + ago + 0) / 3 (RN17); gasto em folha entra no total
- [ ] Resumo do mês → competência vazia → tudo zero
- [ ] Fachada → `registrarGasto` grava e lança a saída como o `PUT`; `resumoDoMes` igual ao endpoint
- [ ] Modularidade → só os oito tipos de `fluxo` são públicos
- [ ] Propriedade → para qualquer conjunto de gastos de uma competência, total gasto = Σ dos totais por categoria

**Checklist de validação:**
- [ ] RF39, RF50, RF51, RN17 atendidos; critérios de aceitação do PRD revisados e marcados
- [ ] Alvo `make resumo` testado rodando o `make`
- [ ] Testes desta fase e das anteriores passando; gate de 90% passando

**Critério de avanço:** checklist completo → validação local de ponta a ponta e, quando o usuário pedir, PR da `feature/fluxo` para a `main`.

---

## 5. Ordem de Execução e Dependências

```
Fase 1 → Fase 2 → Fase 3 → Fase 4 → Fase 5 → Fase 6 → Fase 7
```

- A Fase 1 vem antes de tudo: gasto, ganho e holerite usam os métodos novos da fachada, e o holerite precisa dos tipos `FGTS`/`PREVIDENCIA` e do `DiasUteis`.
- A Fase 5 depende das Fases 3 e 4 (o holerite cria gasto em folha e ganho `SALARIO` pelos mesmos repositórios).
- A Fase 6 só traduz o PDF para o contrato da Fase 5; se o layout real tiver uma surpresa, nada da regra muda.
- A Fase 7 lê o que as outras gravam.
- Nenhuma fase roda em paralelo: migrations e serviços se encadeiam.

## 6. Contratos e Interfaces

### 6.1 Schema

`V006__contas_bloqueado.sql`:

```sql
ALTER TABLE contas.contas DROP CONSTRAINT contas_tipo_check;
ALTER TABLE contas.contas ADD CONSTRAINT contas_tipo_check CHECK (tipo IN
    ('CORRENTE','POUPANCA','INVESTIMENTO','CARTEIRA','BENEFICIO','FGTS','PREVIDENCIA'));
```

`V007__fluxo.sql` (esboço; nomes de constraint e índices finais na implementação):

```sql
CREATE SCHEMA fluxo;

CREATE TABLE fluxo.categorias (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    nome TEXT NOT NULL CHECK (btrim(nome) <> ''),
    predefinida BOOLEAN NOT NULL DEFAULT false,
    ativa BOOLEAN NOT NULL DEFAULT true,
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX categorias_nome_unico ON fluxo.categorias (lower(btrim(nome)));

CREATE TABLE fluxo.holerites (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    competencia DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    origem TEXT NOT NULL CHECK (origem IN ('PDF','MANUAL')),
    hash TEXT NOT NULL,                       -- SHA-256 do PDF ou do JSON canônico
    total_vencimentos, total_descontos, liquido NUMERIC(19,2) NOT NULL,
    salario_base, base_inss, base_fgts, fgts_mes, base_irrf NUMERIC(19,2),
    contrapartida_previdencia NUMERIC(19,2) NOT NULL DEFAULT 0,   -- RN19, T7
    data_adiantamento DATE, data_liquido DATE NOT NULL,
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ,
    CONSTRAINT totais_coerentes CHECK (total_vencimentos - total_descontos = liquido)
);
CREATE UNIQUE INDEX holerites_competencia_unica ON fluxo.holerites (competencia) WHERE deleted_at IS NULL;

CREATE TABLE fluxo.holerite_arquivos (holerite_id UUID PRIMARY KEY REFERENCES fluxo.holerites (id),
    conteudo BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE fluxo.holerite_rubricas (
    holerite_id UUID NOT NULL REFERENCES fluxo.holerites (id), ordem INT NOT NULL,
    codigo TEXT NOT NULL, descricao TEXT NOT NULL, referencia NUMERIC(19,2),
    vencimento NUMERIC(19,2), desconto NUMERIC(19,2),   -- exatamente como no holerite
    PRIMARY KEY (holerite_id, ordem),
    CHECK ((vencimento IS NULL) <> (desconto IS NULL))
);

CREATE TABLE fluxo.rubricas_catalogo (
    codigo TEXT PRIMARY KEY, descricao TEXT NOT NULL,
    natureza TEXT NOT NULL CHECK (natureza IN ('PROVENTO','INSS','IRRF','PREVIDENCIA_BASICA',
        'PREVIDENCIA_VOLUNTARIA','ADIANTAMENTO','GASTO','OUTRO_DESCONTO')),
    categoria_id UUID REFERENCES fluxo.categorias (id),
    a_classificar BOOLEAN NOT NULL DEFAULT false,
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((natureza = 'GASTO') = (categoria_id IS NOT NULL))
);
-- Seed: 0001 PROVENTO, /314 INSS, /507 GASTO (Transporte), 2363 PREVIDENCIA_BASICA,
--       2364 PREVIDENCIA_VOLUNTARIA, /B02 ADIANTAMENTO.

CREATE TABLE fluxo.regras_contrapartida (
    a_partir_de DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM a_partir_de) = 1),
    percentual_da_basica NUMERIC(7,4) NOT NULL CHECK (percentual_da_basica >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);  -- só INSERT (trigger impedir_alteracao, T7); seed: 2000-01-01 → 1.0000

CREATE TABLE fluxo.configuracao_folha (
    id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),  -- linha única
    conta_salario, conta_fgts, conta_previdencia UUID NOT NULL REFERENCES contas.contas (id),
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fluxo.ganhos (
    id UUID PRIMARY KEY,                       -- do cliente; do servidor quando vem do holerite
    competencia DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    descricao TEXT NOT NULL, tipo TEXT NOT NULL CHECK (tipo IN ('SALARIO','PLR','FREELA','REEMBOLSO','OUTRO')),
    bruto NUMERIC(19,2) NOT NULL CHECK (bruto >= 0), liquido NUMERIC(19,2) NOT NULL CHECK (liquido >= 0),
    holerite_id UUID REFERENCES fluxo.holerites (id),
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ,
    CONSTRAINT liquido_nao_supera_bruto CHECK (liquido <= bruto),
    CHECK ((tipo = 'SALARIO') = (holerite_id IS NOT NULL))
);

CREATE TABLE fluxo.recebimentos (
    id UUID PRIMARY KEY,                       -- referência da ENTRADA em contas (T3, T5, T6)
    ganho_id UUID REFERENCES fluxo.ganhos (id), -- nulo: adiantamento ainda solto (RF34)
    competencia DATE NOT NULL, papel TEXT NOT NULL CHECK (papel IN ('AVULSO','ADIANTAMENTO','LIQUIDO')),
    valor NUMERIC(19,2) NOT NULL CHECK (valor > 0), data DATE NOT NULL,
    conta_id UUID NOT NULL REFERENCES contas.contas (id), lancado_antes BOOLEAN NOT NULL DEFAULT false,
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ
);

CREATE TABLE fluxo.lancamentos_folha (
    id UUID PRIMARY KEY,                       -- referência determinística (T6)
    holerite_id UUID NOT NULL REFERENCES fluxo.holerites (id),
    papel TEXT NOT NULL CHECK (papel IN ('FGTS','PREV_BASICA','PREV_CONTRAPARTIDA','PREV_VOLUNTARIA')),
    valor NUMERIC(19,2) NOT NULL CHECK (valor > 0), data DATE NOT NULL,
    conta_id UUID NOT NULL REFERENCES contas.contas (id),
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ
);

CREATE TABLE fluxo.gastos (
    id UUID PRIMARY KEY,
    data DATE NOT NULL, descricao TEXT NOT NULL, valor NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    categoria_id UUID NOT NULL REFERENCES fluxo.categorias (id),
    forma TEXT NOT NULL CHECK (forma IN ('A_VISTA','DESCONTO_EM_FOLHA')),
    conta_id UUID REFERENCES contas.contas (id),
    holerite_id UUID REFERENCES fluxo.holerites (id),
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ,
    CONSTRAINT forma_coerente CHECK (
        (forma = 'A_VISTA'           AND conta_id IS NOT NULL AND holerite_id IS NULL) OR
        (forma = 'DESCONTO_EM_FOLHA' AND holerite_id IS NOT NULL AND conta_id IS NULL))
);
CREATE INDEX gastos_data_idx ON fluxo.gastos (data DESC) WHERE deleted_at IS NULL;
CREATE INDEX gastos_categoria_idx ON fluxo.gastos (categoria_id) WHERE deleted_at IS NULL;
CREATE INDEX ganhos_competencia_idx ON fluxo.ganhos (competencia DESC) WHERE deleted_at IS NULL;
```

### 6.2 Tipos públicos de `fluxo`

```java
public record GastoId(UUID valor) {}
public record GanhoId(UUID valor) {}
public record CategoriaId(UUID valor) {}

public record RegistrarGasto(GastoId id, LocalDate data, String descricao, Dinheiro valor,
                             CategoriaId categoria, ContaId conta) {}

public record ResumoMes(Competencia competencia, Dinheiro totalGasto, Dinheiro recebido, Dinheiro sobra,
                        List<PorCategoria> categorias) {
    public record PorCategoria(CategoriaId categoria, String nome, Dinheiro total, Dinheiro mediaTresMeses) {}
}

public interface FluxoFacade {
    ResumoMes resumoDoMes(Competencia competencia);
    GastoId registrarGasto(RegistrarGasto comando);
}

public record GastoLancado(GastoId gastoId, LocalDate data, Dinheiro valor, CategoriaId categoriaId) {}
public record GanhoRegistrado(GanhoId ganhoId, Competencia competencia, Dinheiro liquido) {}
```

### 6.3 Contrato interno do holerite

```java
/** Saída do leitor de PDF e entrada do lançamento manual: o holerite como veio, sem interpretação. */
record HoleriteLido(Competencia competencia, List<RubricaLida> rubricas, Dinheiro totalVencimentos,
                    Dinheiro totalDescontos, Dinheiro liquido, BasesLidas bases) {}

record RubricaLida(String codigo, String descricao, BigDecimal referencia,
                   Dinheiro vencimento /* ou null */, Dinheiro desconto /* ou null */) {}

record BasesLidas(Dinheiro salarioBase, Dinheiro baseInss, Dinheiro baseFgts, Dinheiro fgtsMes,
                  Dinheiro baseIrrf) {}

interface LeitorDeHolerite {
    HoleriteLido ler(byte[] pdf);   // lança HoleriteIlegivelException(campo)
}
```

### 6.4 Acréscimos em `contas`

```java
public interface ContasFacade {
    // … métodos existentes …
    Optional<Conta> buscarConta(ContaId id);
    void alterarLancamento(UUID referencia, ContaId conta, Dinheiro valor, LocalDate data);
    void excluirLancamento(UUID referencia);
    void restaurarLancamento(UUID referencia);
}
// Consolidado: { posicaoGeral, dividas, saldoEmConta, bloqueado, contas[] }
```

### 6.5 API

| Método | Caminho | Sucesso | Erros |
|---|---|---|---|
| GET | `/api/categorias?incluirInativas=` | 200 | — |
| POST | `/api/categorias` | 201 | 409 nome em uso, 422 |
| PATCH | `/api/categorias/{id}` | 200 | 404, 409, 422 |
| PUT | `/api/gastos/{id}` | 201 / 200 reenvio | 404 categoria/conta, 409 divergente/excluído, 422 |
| PATCH | `/api/gastos/{id}` | 200 | 404, 422 (gasto do holerite, categoria inativa) |
| DELETE | `/api/gastos/{id}` | 204 | 422 gasto do holerite |
| POST | `/api/gastos/{id}/restaurar` | 200 | 404 |
| GET | `/api/gastos?competencia=&categoria=` | 200 | 422 competência inválida |
| PUT | `/api/ganhos/{id}` | 201 / 200 | 404, 409, 422 (salário, líquido > bruto) |
| PATCH / DELETE / POST restaurar | `/api/ganhos/{id}` | 200 / 204 / 200 | 404, 422 ganho do holerite |
| GET | `/api/ganhos?competencia=` | 200 | 422 |
| PUT | `/api/folha/configuracao` | 200 | 404, 422 tipo de conta errado |
| POST | `/api/folha/holerites` (multipart) | 201 / 200 | 409, 413, 422 (ilegível, inconsistente, sem configuração, não é PDF) |
| POST | `/api/folha/holerites/manual` | 201 / 200 | 409, 422 |
| GET | `/api/folha/holerites?ano=`, `/{id}`, `/{id}/pdf` | 200 | 404 (PDF de holerite manual → 404) |
| DELETE / POST restaurar | `/api/folha/holerites/{id}` | 204 / 200 | 404 |
| PATCH | `/api/folha/holerites/{id}/previdencia` | 200 | 404, 422 |
| PUT | `/api/folha/adiantamentos/{id}` | 201 / 200 | 409, 422 |
| GET / PATCH | `/api/folha/rubricas`, `/{codigo}` | 200 | 404, 422 |
| GET / POST | `/api/folha/contrapartida` | 200 / 201 | 409 vigência repetida, 422 |
| GET | `/api/folha/resumo?ano=` | 200 | 422 |
| GET | `/api/fluxo/resumo?competencia=` | 200 | 422 |

Todos: 401 sem token (SEC01).

## 7. Riscos Técnicos

| Risco | Fase afetada | Mitigação |
|---|---|---|
| Layout real ter detalhes que o sintético não reproduz (espaçamento, quebra de linha) | 6 | Leitor por texto ordenado por posição, com o sintético gerado a partir da estrutura do real; validação com o PDF real fora do repositório antes de fechar a fase |
| Reaplicação de efeitos duplicar lançamentos | 5 | Referências determinísticas (T6) e índice único de referência em `contas` |
| Reclassificar rubrica mexer na contrapartida gravada | 5 | A contrapartida é coluna do holerite e só muda pelo RF35b; teste explícito |
| Mudar o `CHECK` de tipo em `contas` com dados existentes | 1 | A constraint nova é um superconjunto da antiga; nenhuma linha fica inválida |
| Dados pessoais do PDF vazarem em log ou erro | 6 | SEC02 com teste de captura de log; erro aponta o campo, não o conteúdo |
| Volume do `BYTEA` crescer | 6 | ~15 KB por holerite, 12 por ano; tabela separada (T8) para não pesar nas listagens |
| `alterarLancamento` em movimentação anterior à última conciliação não mudar o saldo atual | 1 | Comportamento correto de `contas` (P2 de contas); documentado e testado |

## 8. Rastreabilidade PRD → SPEC

| Requisito PRD | Fase | Observação |
|---|---|---|
| RF01 | 2 (dados), 3 (API) | — |
| RF02, RF03, RF04 | 3 | — |
| RF10, RF11, RF12, RF13 | 3 | Reflexo pela fachada (T2) |
| RF14 | 2 | `FormaPagamento` selada |
| RF15 | 3 (API), 5 (criação) | — |
| RF20–RF24 | 4 | T5 |
| RF30 | 5 | Tipo das contas pela `buscarConta` |
| RF31 | 6 | — |
| RF32, RF33, RF34 | 5 | T6 |
| RF35, RF36 | 5 | T11 |
| RF35a, RF35b | 5 | T7 |
| RF37 | 6 | T8 |
| RF38 | 5 | — |
| RF39 | 7 | T10 |
| RF40, RF41, RF42 | 1 | T2, T3 |
| RF50, RF51 | 7 | T14 |
| RF52 | 3 (`GastoLancado`), 4 (`GanhoRegistrado`) | T13 |
| RNF01 | 1, 2 | V006, V007 |
| RNF02 | 2 | — |
| RNF03, RNF04, RNF05 | 6 | — |
| RNF06 | todas | Segurança e formato de erro existentes |
| RNF07 | todas | Gate JaCoCo |
| RNF08 | 2 | — |
| RN01 | 3 | — |
| RN02 | 2, 4, 5 | — |
| RN03 | 2, 3, 4 | — |
| RN04 | 3, 4, 5 | Transação única |
| RN05, RN06 | 2 (banco), 3 | — |
| RN07, RN08, RN09 | 3, 4 | — |
| RN10, RN11 | 5 (manual), 6 (PDF) | T9 |
| RN12, RN13, RN14, RN15 | 5 | — |
| RN16 | 1 | — |
| RN17 | 7 | — |
| RN18 | 1 (`DiasUteis`), 5 | T4 |
| RN19 | 5 | T7 |
| SEC01 | todas | Teste de 401 por controller |
| SEC02 | 5, 6 | Captura de log |
| SEC03, SEC04, SEC05, SEC06 | 6 | T8, T12 |
| P1 | 5 | FGTS e previdência na data do líquido |
| P2 | 5 | IRRF entra "a classificar" |
| P3 | 5 | Catálogo; confirmar com o holerite de dezembro |
| P4 | 4 | — |
| P5 | 1 | T4 |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|--------|------------|----------------------------|
| 1.0 | 2026-11-14 | Criação inicial: 7 fases aprovadas; decisões técnicas T1–T15; PDFBox 3.0.8 como dependência nova (Fase 6) |
| 1.1 | 2026-11-15 | Aprovado pelo usuário |
