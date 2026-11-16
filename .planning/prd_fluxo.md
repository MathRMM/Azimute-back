# PRD — Azimute Core: Módulo `fluxo`, parte 1, com holerite (Fase 4)

**Data:** 2026-11-14
**Status:** aprovado

**Fontes:** `assistente-docs/00-arquitetura.md` (ordem de construção, fase 4), `03-modulo-fluxo.md`, `.planning/prd_contas.md` (fachada e padrões já aprovados) e um holerite real do usuário, usado só para levantar o layout. Quando este PRD diverge da doc, prevalece o PRD; as divergências são decisões novas registradas no Histórico de Revisões.

---

## 1. Contexto e Problema

As fases 1 a 3 estão prontas: kernel `comum`, calculadora, `auth` e `contas` (contas reais, movimentações, conciliação e a posição geral). A fachada de `contas` já aceita entradas e saídas vindas de outro módulo, mas nada a usa ainda. Por isso, hoje toda conciliação de conta de giro mostra uma divergência grande: os gastos e ganhos do dia a dia não são lançados.

O próximo módulo pela ordem de construção é `fluxo`: ganhos, gastos e categorias. Na entrevista, o usuário trouxe uma necessidade maior que a da doc 03: ter uma **previsão precisa dos recebimentos**, acompanhar **quanto pagou de imposto**, o **FGTS acumulado** (importante para um financiamento imobiliário) e a **previdência privada**. O vínculo é CLT e não há outra atividade, então a renda é praticamente toda explicada pelo holerite mensal.

**Problema concreto:**
1. Gastos e ganhos não chegam às contas, e o saldo calculado se afasta do real.
2. O holerite tem a informação de toda a renda (bruto, descontos, impostos, FGTS, previdência), mas hoje ela fica só no PDF.
3. FGTS e previdência privada são dinheiro do usuário, mas bloqueado; não aparecem em lugar nenhum.

O sistema é de **usuário único**. A escolha nesta fase é pelo mais simples que resolve o problema.

### O holerite de referência

O layout é o do empregador atual: "Demonstrativo de Pagamento", texto selecionável (não é imagem), em colunas de largura fixa, com rodapé `#v.1 JUL/2019`. Estrutura levantada:

- **Cabeçalho:** empresa, CNPJ, mês e ano por extenso (ex.: "Outubro 2026"), matrícula, nome, admissão, cargo.
- **Rubricas:** linhas `Código | Descrição | Referência | Vencimentos | Descontos`.
- **Totais:** total de vencimentos, total de descontos e **líquido**.
- **Bases:** salário base, base INSS, base FGTS, **FGTS do mês**, base IRRF e faixa IRRF.
- **Não traz a data de pagamento.**

Rubricas vistas no exemplo:

| Código | Descrição | Natureza |
|---|---|---|
| `0001` | Salário Mensal | provento |
| `/314` | INSS | imposto |
| `/507` | Vale-Transporte | desconto que vira gasto (Transporte) |
| `2363` | Prev Priv Bas Func | previdência privada, contribuição **básica** (a empresa contribui o mesmo valor) |
| `2364` | Prev Priv Vol Func | previdência privada, contribuição **voluntária** (sem contrapartida) |
| `/B02` | Decto Adto Salário | adiantamento já pago no mês |

O **adiantamento** (cerca de 50% do salário) cai na conta no meio do mês, **sem demonstrativo próprio**, e é descontado no holerite mensal. Por isso o líquido do holerite é só o saldo do fim do mês: o que o usuário recebe no mês é o adiantamento mais o líquido. No exemplo não houve IRRF (base abaixo da faixa de isenção); o código da rubrica de IRRF ainda é desconhecido (P2).

> O PDF real não entra no repositório. Os testes usam um holerite **sintético** no mesmo layout, com nome, matrícula e valores fictícios (SEC03).

## 2. Objetivo

Ao fim da fase 4, o backend deve permitir:

1. **Importar o holerite em PDF**: o sistema lê as rubricas, os totais e as bases, guarda o PDF e os dados, e a partir dele lança sozinho o ganho, os recebimentos nas contas, o depósito do FGTS, a contribuição da previdência e os descontos que são gasto. Se a leitura falhar, o mesmo holerite pode ser lançado à mão.
2. **Acompanhar a folha**: por mês e por ano, bruto, INSS, IRRF, previdência, FGTS e líquido; FGTS e previdência acumulados como saldo de conta, conciliável com o extrato real.
3. **Registrar gastos à vista** (da conta de onde o dinheiro saiu), com categoria, de forma idempotente para a fila offline do app; editar, excluir e restaurar.
4. **Registrar ganhos avulsos** (reembolso, freela, outro), também idempotentes, editáveis e com exclusão lógica.
5. **Manter categorias de gasto**: as pré-definidas, criação no meio do registro, renomear e desativar.
6. Ver o **resumo do mês**: total gasto (por categoria, comparado com a média dos 3 meses anteriores), total recebido e sobra.
7. Deixar pronta a **fachada** (`resumoDoMes`, `registrarGasto`) e os **eventos** (`GastoLancado`, `GanhoRegistrado`) que `sistema` e `agenda` vão usar.

## 3. Requisitos Funcionais

### 3.1 Categorias de gasto

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF01 | A migration cria as categorias pré-definidas: Alimentação, Moradia, Transporte, Saúde, Educação, Lazer, Assinaturas, Vestuário, Outros | Alta |
| RF02 | `POST /api/categorias { nome }` cria uma categoria e devolve o ID, para uso no meio do registro de gasto (criação inline) | Alta |
| RF03 | `GET /api/categorias` lista as ativas, em ordem alfabética; com filtro para incluir as inativas | Alta |
| RF04 | `PATCH /api/categorias/{id} { nome?, ativa? }` renomeia ou desativa/reativa. Os gastos antigos passam a mostrar o nome novo. Categoria não é excluída (RN05) | Alta |

### 3.2 Gastos

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF10 | `PUT /api/gastos/{id} { data, descricao, valor, categoria, conta }` registra um gasto **à vista** com ID gerado pelo cliente: grava o gasto e lança a saída na conta pela fachada de `contas`, numa transação só. Mesmo ID e mesmo corpo → 200 com o existente; corpo diferente → 409 | Alta |
| RF11 | `PATCH /api/gastos/{id}` altera `data`, `descricao`, `valor`, `categoria` e `conta`; a saída em `contas` acompanha (valor, data e conta) | Alta |
| RF12 | `DELETE /api/gastos/{id}` faz exclusão lógica do gasto e da saída correspondente em `contas`; `POST /api/gastos/{id}/restaurar` desfaz as duas | Alta |
| RF13 | `GET /api/gastos?competencia=AAAA-MM&categoria=` lista os gastos da competência (pela `data`), da mais recente para a mais antiga, com o nome da categoria e a forma de pagamento | Alta |
| RF14 | A forma de pagamento é um tipo fechado: `A_VISTA` (com conta) e `DESCONTO_EM_FOLHA` (com o holerite de origem, sem conta). `CREDITO` entra na fase 5, junto com `cartoes` | Alta |
| RF15 | Gasto `DESCONTO_EM_FOLHA` nasce só pela importação do holerite (RF33). Pela API de gastos, só a `categoria` dele pode mudar; valor e data mudam pelo holerite | Média |

### 3.3 Ganhos

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF20 | `PUT /api/ganhos/{id} { competencia, descricao, tipo, bruto, liquido, conta, recebidoEm }` registra um ganho avulso já recebido, com ID do cliente e as mesmas regras de reenvio do RF10, e lança a entrada na conta | Alta |
| RF21 | `tipo` ∈ `SALARIO`, `PLR`, `FREELA`, `REEMBOLSO`, `OUTRO` (lista fixa). Ganho `SALARIO` vem do holerite; pela API avulsa, os outros quatro | Alta |
| RF22 | `PATCH /api/ganhos/{id}`, `DELETE /api/ganhos/{id}` e `POST /api/ganhos/{id}/restaurar`, com o mesmo comportamento do RF11–RF12 sobre a entrada em `contas`. Ganho vindo do holerite só muda pelo holerite | Alta |
| RF23 | `GET /api/ganhos?competencia=AAAA-MM` lista os ganhos da competência com bruto, líquido, tipo e os recebimentos (data, valor, conta) | Alta |
| RF24 | Um ganho pode ter **mais de um recebimento** (data, valor, conta). O líquido do ganho é a soma dos recebimentos. Cada recebimento é uma entrada separada em `contas` | Alta |

### 3.4 Holerite

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF30 | `PUT /api/folha/configuracao { contaSalario, contaFgts, contaPrevidencia }` define, uma vez, para onde vão o salário, o FGTS e a previdência. Sem configuração, a importação responde 422 | Alta |
| RF31 | `POST /api/folha/holerites` (multipart: o PDF e, opcionais, `dataLiquido` e `dataAdiantamento`) lê o PDF no layout de referência e devolve o holerite lido, com as rubricas, totais e bases. Sem as datas, valem as datas padrão da RN18 | Alta |
| RF32 | `POST /api/folha/holerites/manual` aceita o mesmo conteúdo em JSON (competência, rubricas, bases, datas) e passa pelo mesmo processamento do RF31. É a saída quando o PDF não pode ser lido | Alta |
| RF33 | Ao importar, numa transação só, o sistema: (a) cria o ganho `SALARIO` da competência, com bruto = total de vencimentos e os recebimentos **adiantamento** (valor da rubrica de adiantamento, na `dataAdiantamento`) e **líquido** (na `dataLiquido`), ambos na conta do salário; (b) lança o **FGTS do mês** como entrada na conta FGTS; (c) lança na conta de previdência, como **entradas separadas**: a contribuição **básica**, a **contrapartida da empresa** (calculada pela RN19) e a contribuição **voluntária**; (d) cria um gasto `DESCONTO_EM_FOLHA` para cada rubrica classificada como gasto (ex.: vale-transporte → Transporte) | Alta |
| RF34 | O adiantamento pode ser lançado **antes** do holerite chegar: `PUT /api/folha/adiantamentos/{id} { competencia, valor, data? }` (sem data, vale a padrão da RN18) lança a entrada na conta do salário. Ao importar o holerite da mesma competência, o sistema **associa** esse recebimento ao ganho, sem lançar de novo; se o valor divergir da rubrica, o holerite prevalece e a entrada é ajustada | Alta |
| RF35 | **Catálogo de rubricas**: `GET /api/folha/rubricas` e `PATCH /api/folha/rubricas/{codigo} { natureza, categoria? }`. Naturezas: `PROVENTO`, `INSS`, `IRRF`, `PREVIDENCIA_BASICA`, `PREVIDENCIA_VOLUNTARIA`, `ADIANTAMENTO`, `GASTO` (com categoria), `OUTRO_DESCONTO`. A migration já traz as seis rubricas do exemplo classificadas | Alta |
| RF36 | Rubrica com código fora do catálogo é importada como `OUTRO_DESCONTO` ou `PROVENTO` (pela coluna em que aparece), entra no catálogo como **a classificar**, e a resposta da importação avisa. Reclassificar uma rubrica reprocessa os holerites que a usam (RN13) | Média |
| RF37 | `GET /api/folha/holerites?ano=` lista os holerites; `GET /api/folha/holerites/{id}` mostra um com as rubricas; `GET /api/folha/holerites/{id}/pdf` devolve o PDF original | Alta |
| RF38 | `DELETE /api/folha/holerites/{id}` faz exclusão lógica do holerite e de tudo o que ele gerou (ganho, recebimentos, FGTS, previdência, gastos em folha), refletida em `contas`; `POST .../restaurar` desfaz | Média |
| RF35a | **Regra de contrapartida da previdência**, com vigência: `GET /api/folha/contrapartida` lista o histórico e `POST /api/folha/contrapartida { aPartirDe (competência), percentualDaBasica }` registra uma regra nova. A migration traz a regra inicial: **100% da básica** (a empresa põe o mesmo valor). Regra nova vale só a partir da competência informada | Alta |
| RF35b | `PATCH /api/folha/holerites/{id}/previdencia { contrapartida }` corrige a contrapartida de um holerite específico (ex.: valor confirmado no extrato da previdência), ajustando a entrada na conta | Média |
| RF39 | `GET /api/folha/resumo?ano=` mostra, por mês e no total do ano: bruto, INSS, IRRF, total de impostos, previdência **separada** em básica, contrapartida da empresa e voluntária (e o total depositado), FGTS depositado e líquido recebido. O FGTS e a previdência **acumulados** são o saldo das contas FGTS e previdência (RF41) | Alta |

### 3.5 Mudanças em `contas`

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF40 | Novos tipos de conta: `FGTS` e `PREVIDENCIA`. Como as outras, podem ser conciliadas com o extrato real (o rendimento do FGTS e da previdência entra pela conciliação ou como rendimento; a contrapartida da empresa na básica vem do holerite, RN19) | Alta |
| RF41 | O consolidado ganha o campo **`bloqueado`**: soma das contas `FGTS` e `PREVIDENCIA` ativas. Elas **não entram** na `posicaoGeral` nem no `saldoEmConta`, porque o dinheiro não está disponível (RN12) | Alta |
| RF42 | A fachada de `contas` ganha `alterarLancamento(ref, conta, valor, data)`, `excluirLancamento(ref)` e `restaurarLancamento(ref)` para entradas e saídas, chamadas só pelo módulo que as criou. A API de `contas` continua sem editar nem excluir `ENTRADA` e `SAIDA` (T2 do SPEC de contas) | Alta |

### 3.6 Resumo, fachada e eventos

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF50 | `GET /api/fluxo/resumo?competencia=AAAA-MM` devolve: total gasto (à vista + desconto em folha), total por categoria com a **média dos 3 meses anteriores** de cada uma, total recebido (líquido dos ganhos) e sobra (recebido − gasto) | Alta |
| RF51 | Interface pública `FluxoFacade` com `resumoDoMes(competencia)` e `registrarGasto(comando)` (usada pela captura rápida da `agenda`, fase 15) | Alta |
| RF52 | Eventos publicados depois do commit (Spring Modulith): `GastoLancado { gastoId, data, valor, categoriaId }` e `GanhoRegistrado { ganhoId, competencia, liquido }` | Média |

## 4. Requisitos Não Funcionais

Valem os da fundação e de `contas`. Específicos desta fase:

| ID | Requisito | Categoria |
|-------|---------------------------------------------------|-----------------|
| RNF01 | Schema próprio `fluxo`, criado por migration Flyway (`V006`); mudanças em `contas` (tipos novos) em migration própria | Manutenibilidade |
| RNF02 | Forma de pagamento como `sealed interface` (`AVista`, `DescontoEmFolha`; `Credito` na fase 5), para todo `switch` tratar cada forma | Manutenibilidade |
| RNF03 | Leitura do PDF por extração de texto (o PDF tem texto selecionável), com biblioteca madura e mantida; sem OCR | Compatibilidade |
| RNF04 | O leitor do holerite fica isolado atrás de uma interface (um leitor por layout), para trocar ou adicionar layout sem mexer no processamento | Manutenibilidade |
| RNF05 | Importação de um holerite responde em menos de 2 s | Performance |
| RNF06 | Todos os endpoints exigem token e usam o formato único de erro `{ erro, mensagem, detalhes }` | Compatibilidade |
| RNF07 | Cobertura mínima de 90% de linhas e de ramos (gate JaCoCo), com testes unitários e de integração | Manutenibilidade |
| RNF08 | Tabelas seguem o padrão `created_at` / `updated_at` / `deleted_at` (RN14 de `contas`) | Manutenibilidade |

## 5. Regras de Negócio

- **RN01 — A conta é o atributo, não a modalidade.** Pix, débito e dinheiro não são formas de pagamento: o gasto à vista registra **de qual conta** o dinheiro saiu.
- **RN02 — Líquido não supera o bruto**, no ganho e no holerite, garantido também por constraint no banco.
- **RN03 — Valor positivo** em gasto, ganho e recebimento, com no máximo 2 casas decimais.
- **RN04 — Uma transação por registro.** Gasto e saída na conta, ganho e entradas, holerite e tudo o que ele gera: ou tudo grava, ou nada grava.
- **RN05 — Categoria não se exclui, se desativa.** Categoria inativa some das listas de seleção, mas os gastos antigos continuam apontando para ela. Gasto novo em categoria inativa → 422.
- **RN06 — Nome de categoria único**, ignorando maiúsculas e espaços nas pontas.
- **RN07 — Conta inexistente ou inativa** é recusada (regra de `contas`): 404 e 422.
- **RN08 — Data futura é aceita** em gasto e ganho, como em `contas` (RN15 de `contas`).
- **RN09 — Competência do gasto é a da sua data.** Competência do ganho é informada (salário de outubro pode cair em novembro).
- **RN10 — Holerite consistente.** Antes de lançar qualquer coisa, o sistema confere: soma das rubricas de vencimento = total de vencimentos; soma das de desconto = total de descontos; vencimentos − descontos = líquido. Se não bater, a importação é recusada com 422 e nada é gravado.
- **RN11 — Um holerite mensal por competência.** O mesmo PDF reenviado (mesmo hash) → 200 com o existente; outro PDF da mesma competência → 409 (exclua o anterior antes).
- **RN12 — O que é cada rubrica vem do catálogo**, não do texto. `INSS` e `IRRF` somam impostos; `PREVIDENCIA_BASICA` e `PREVIDENCIA_VOLUNTARIA` vão para a conta de previdência (RN19); `ADIANTAMENTO` é o recebimento do meio do mês; `GASTO` vira gasto em folha na categoria definida; `OUTRO_DESCONTO` só reduz o líquido; `PROVENTO` compõe o bruto.
- **RN13 — Reclassificar refaz o efeito.** Mudar a natureza de uma rubrica reprocessa os holerites que a usam, ajustando gastos em folha, previdência e totais de imposto. Os dados lidos do PDF não mudam, e a **contrapartida já gravada de cada holerite também não** (RN19).
- **RN14 — Recebido no mês = adiantamento + líquido do holerite.** É o líquido do ganho `SALARIO` e o que alimenta o resumo do mês.
- **RN15 — Ganho do holerite e gasto em folha só mudam pelo holerite** (exceto a categoria do gasto em folha, RF15).
- **RN16 — FGTS e previdência são patrimônio bloqueado**: contam no `bloqueado` do consolidado, nunca na posição geral nem no saldo em conta.
- **RN17 — A média de comparação** de uma categoria é a média simples dos totais dos 3 meses anteriores à competência (meses sem gasto contam como zero).
- **RN18 — Datas padrão do salário.** O pagamento é no próprio mês da competência: o **adiantamento no dia 15** e o **líquido no último dia do mês**. Se a data cair em dia não útil, **antecipa** para o dia útil anterior (nunca adia). As datas informadas na importação prevalecem sobre as padrão. Dia útil: ver P5.
- **RN19 — Previdência em três campos gravados.** Cada holerite guarda três valores de previdência: **básica** e **voluntária**, exatamente como vieram no holerite, e **contrapartida da empresa**, gravada no momento da importação. A contrapartida é calculada uma vez, pela regra **vigente na competência do holerite** (RF35a): `contrapartida = básica × percentual da regra` (hoje 100%, então básica + contrapartida = 2 × básica). A voluntária não tem contrapartida. Total depositado no mês = básica + contrapartida + voluntária. Depois de gravada, a contrapartida só muda por correção explícita daquele holerite (RF35b): **regra nova não reprocessa holerites antigos**, para que cada mês mantenha o valor que valia na época e a mudança fique rastreável pela data de vigência.

## 6. Requisitos de Segurança

- **SEC01:** Todos os endpoints exigem `Authorization: Bearer <token>`; sem token → 401.
- **SEC02:** Valores, rubricas e o conteúdo do PDF não são escritos no log. Erro de leitura do PDF informa a linha ou campo que falhou, não o conteúdo.
- **SEC03:** O holerite real nunca entra no repositório nem nos testes; os testes usam um PDF sintético com dados fictícios no mesmo layout.
- **SEC04:** Upload aceito só se o arquivo começar com a assinatura de PDF (`%PDF`) e tiver até 2 MB; outros → 422 (ou 413 acima do tamanho).
- **SEC05:** O PDF guardado no banco tem dados pessoais (nome, matrícula, salário). Ele só sai pelo endpoint do RF37, com token; o backup já é cifrado (doc 00).
- **SEC06:** O PDF é lido como dado: nada nele é executado (sem JavaScript, formulários ou anexos), e o leitor tem limite de páginas.

## 7. Casos de Uso

### Fluxo Principal A — Importar o holerite do mês
*(Valores fictícios.)*
1. Configuração feita uma vez: conta do salário (corrente), conta FGTS, conta previdência.
2. No dia 15, o adiantamento de R$ 2.500,00 cai na conta; o usuário lança o adiantamento (RF34), sem precisar informar a data.
3. No último dia do mês (ou no dia útil anterior), o usuário envia o PDF; a data do líquido é a padrão (RN18).
4. O holerite tem salário de R$ 5.000,00, INSS de R$ 480,00, vale-transporte de R$ 300,00, previdência básica de R$ 50,00 e voluntária de R$ 250,00, adiantamento de R$ 2.500,00, líquido de R$ 1.420,00 e FGTS do mês de R$ 400,00.
5. O sistema confere os totais, cria o ganho `SALARIO` de R$ 5.000,00 bruto com recebimentos de R$ 2.500,00 (já lançado, só associado) e R$ 1.420,00; deposita R$ 400,00 na conta FGTS; na conta previdência, lança R$ 50,00 (básica), R$ 50,00 (contrapartida da empresa) e R$ 250,00 (voluntária), total de R$ 350,00; e cria o gasto em folha de R$ 300,00 em Transporte.
6. O resumo da folha passa a mostrar INSS de R$ 480,00 no mês, e o consolidado, o FGTS e a previdência em `bloqueado`.

### Fluxo Principal B — Gasto do dia a dia
1. No mercado, sem sinal, o app gera o ID e guarda o gasto de R$ 87,40 (Alimentação, conta Nubank).
2. Quando a conexão volta, o app envia `PUT /api/gastos/{id}`; o gasto e a saída no Nubank são gravados.
3. A fila reenvia por engano: mesmo ID e corpo → 200, nada duplica.

### Fluxo Principal C — Corrigir
1. O gasto foi lançado como R$ 874,00. O usuário edita para R$ 87,40; a saída no Nubank acompanha.
2. Outro gasto foi lançado em duplicidade; o usuário exclui, e a saída some do saldo (pode restaurar).

### Fluxo Principal D — Fechar o mês
1. O usuário abre o resumo de outubro: total gasto, por categoria, cada uma ao lado da média de jul–set, total recebido e sobra.

### Fluxos Alternativos
- **Alternativo A:** PDF de outro layout ou ilegível → 422 com o campo que falhou; o usuário lança pelo RF32.
- **Alternativo B:** totais do holerite não batem → 422, nada gravado (RN10).
- **Alternativo C:** rubrica nova (ex.: 13º, IRRF) → importa, marca como "a classificar" e avisa; o usuário classifica e o efeito é refeito (RF36, RN13).
- **Alternativo D:** importação sem configuração da folha → 422.
- **Alternativo E:** gasto em categoria inativa ou conta inativa → 422; conta ou categoria inexistente → 404.
- **Alternativo F:** tentativa de editar pela API de gastos um gasto em folha (exceto categoria), ou pela API de ganhos um ganho do holerite → 422.

### Casos de Borda
- Holerite sem adiantamento (rubrica ausente) → um recebimento só, o líquido.
- Adiantamento lançado e holerite com valor diferente → o holerite prevalece; a entrada é ajustada (RF34).
- Mês sem IRRF (isento) → imposto do mês = só INSS.
- Holerite de 13º ou férias no mesmo PDF do mensal: as rubricas novas entram como "a classificar" até o usuário classificar (P3).
- Excluir o holerite com o adiantamento lançado antes: o adiantamento volta a ser um recebimento solto da competência (não é excluído junto).
- Reenvio do mesmo PDF → 200; outro PDF da mesma competência → 409.
- Gasto com valor zero, negativo ou com 3 casas → 422.
- Resumo de competência sem nenhum lançamento → tudo zero; média dos meses sem dados = zero.

## 8. Fora do Escopo

- **Gasto no crédito**, parcelas, prévia de fatura (`/api/gastos/previa`): fase 5, com `cartoes`.
- **Recorrentes, horizonte de 12 meses e previsão do líquido futuro**: fase 6 (`fluxo` parte 2). A previsão vai partir do histórico de holerites guardado nesta fase.
- **Desconto médio por tipo e estimativa de líquido** (`/api/ganhos/desconto-medio`, `/api/ganhos/estimar`): com renda CLT única, o holerite já dá o valor exato; o tema volta na fase 6, se ainda fizer sentido.
- **PLR**: o usuário ainda não recebeu e não conhece a estrutura; entra quando houver um exemplo (o tipo `PLR` já existe para lançamento avulso).
- **Outros layouts de holerite** (outro empregador): o leitor é isolado por layout (RNF04), mas só o atual é implementado.
- **Rendimento do FGTS e da previdência**: não aparece no holerite; entra pela conciliação das contas FGTS e previdência. (A contrapartida da empresa na básica é gravada em cada holerite, RN19.)
- **Orçamento por categoria**: a comparação é com a média dos 3 meses (decisão do usuário); orçamento pode vir depois.
- **Importação de extrato bancário em PDF**: continua com PRD próprio (registrada no PRD de `contas`).
- **Informe de rendimentos e declaração de IR**: o resumo anual da folha (RF39) é a base, mas não gera documento.

## 9. Dependências e Riscos

| Tipo | Descrição | Impacto |
|--------------|--------------------------------------------------------|----------|
| Dependência | Fachada de `contas` (fase 3), com os métodos novos do RF42 | Alto |
| Dependência | Biblioteca de extração de texto de PDF (nova dependência do projeto) | Médio |
| Risco | O empregador muda o layout do demonstrativo → a leitura falha. Mitigação: RN10 recusa em vez de gravar errado; RF32 lança à mão | Médio |
| Risco | Rubrica nova classificada errado distorce impostos ou gastos. Mitigação: "a classificar" e reprocessamento (RF36, RN13) | Médio |
| Risco | O PDF guardado tem dados pessoais. Mitigação: SEC02, SEC05, backup cifrado | Médio |
| Risco | A mudança no consolidado (`bloqueado`, RF41) altera o contrato de `contas`: campo novo, sem remover nenhum; o app ainda não consome | Baixo |
| Risco | A doc `03-modulo-fluxo.md` não prevê holerite, recebimentos múltiplos nem desconto em folha; precisa ser atualizada | Baixo |

## 10. Critérios de Aceitação

- [x] Categorias pré-definidas existem; criar inline, renomear e desativar funcionam; nome repetido → 409; gasto em categoria inativa → 422 (RF01–RF04, RN05, RN06)
- [x] Gasto à vista grava e lança a saída na conta; reenvio idêntico → 200 sem duplicar; corpo diferente → 409 (RF10)
- [x] Editar o gasto ajusta a saída; excluir e restaurar refletem no saldo da conta (RF11, RF12)
- [x] Ganho avulso grava e lança a entrada; edição, exclusão e restauração refletem na conta (RF20–RF22)
- [x] Importar o holerite sintético cria o ganho com dois recebimentos, o FGTS, a previdência e o gasto em folha, com os valores exatos (RF31, RF33)
- [x] A previdência entra como básica, contrapartida e voluntária separadas, os três gravados no holerite; a voluntária não tem contrapartida; básica e voluntária ficam como vieram no holerite (RN19)
- [x] A contrapartida segue a regra vigente na competência; regra nova vale só dali em diante e não altera holerites antigos; correção de um holerite ajusta só ele (RF35a, RF35b, RN19)
- [x] Adiantamento lançado antes é associado sem duplicar; divergência ajusta a entrada (RF34)
- [x] Holerite com totais inconsistentes → 422 e nada gravado (RN10)
- [x] Mesmo PDF reenviado → 200; outro PDF da mesma competência → 409 (RN11)
- [x] Rubrica desconhecida entra como "a classificar"; reclassificar refaz o efeito (RF36, RN13)
- [x] Lançamento manual do holerite produz o mesmo resultado que o PDF (RF32)
- [x] Excluir e restaurar o holerite desfaz e refaz tudo o que ele gerou (RF38)
- [x] Resumo da folha soma INSS, IRRF, previdência, FGTS e líquido por mês e no ano (RF39)
- [x] Consolidado mostra FGTS e previdência em `bloqueado`, fora da posição geral e do saldo em conta (RF40, RF41, RN16)
- [x] Resumo do mês mostra total gasto por categoria com a média dos 3 meses anteriores, recebido e sobra (RF50, RN17)
- [x] Eventos publicados só depois do commit (RF52)
- [x] Nenhum outro módulo acessa a parte interna de `fluxo` (teste do Spring Modulith)
- [x] Upload que não é PDF ou maior que 2 MB é recusado; nada do PDF aparece no log (SEC02, SEC04)
- [x] Todos os endpoints exigem token (SEC01)
- [x] Gate de cobertura de 90% em linhas e ramos continua passando (RNF07)

## 11. Decisões sobre as pendências

Todas aprovadas como recomendado (v1.3).

| # | Pendência | Recomendação | Motivo |
|---|---|---|---|
| P1 | Data do depósito do FGTS e da previdência na conta | **Aprovada (v1.3):** Usar a `dataLiquido` do holerite para os dois | O depósito real do FGTS é até o dia 20 do mês seguinte; a diferença de dias não muda o acumulado e a conciliação acerta |
| P2 | Código da rubrica de IRRF | **Aprovada (v1.3):** Deixar o catálogo sem IRRF até ele aparecer num holerite; quando aparecer, entra como "a classificar" e o usuário marca `IRRF` | Não dá para adivinhar o código do empregador |
| P3 | 13º e férias no mesmo PDF do mensal | **Aprovada (v1.3):** Tratar as rubricas novas pelo catálogo, no mesmo ganho `SALARIO` da competência; confirmar com um holerite de dezembro | O usuário acha que vem no mesmo holerite, mas ainda não tem um exemplo |
| P4 | Ganho `SALARIO` pela API avulsa (sem holerite) | **Aprovada (v1.3):** Recusar: salário só pelo holerite (PDF ou manual, RF32) | Evita dois caminhos para o mesmo dado e duplicidade no mês |
| P5 | O que é dia útil para a RN18 | **Aprovada (v1.3):** Fim de semana e **feriados nacionais** (fixos e móveis, como Carnaval e Sexta-feira Santa, calculados a partir da Páscoa), num calendário do `comum` que `cartoes` reaproveita na fase 5 (vencimento de fatura). Feriado estadual ou municipal fica de fora; a data informada na importação corrige quando precisar | O mesmo conceito de dia útil volta em `cartoes`; feriado local exigiria saber a cidade do banco |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|--------|------------|----------------------------|
| 1.0 | 2026-11-14 | Criação inicial. Escopo: fase 4 (`fluxo` parte 1). Decisões novas em relação à doc 03, tomadas na entrevista: (a) **só gasto à vista** nesta fase; crédito com `cartoes`; (b) gasto e ganho **editáveis** e com **exclusão lógica**; (c) "planejado" da revisão da semana = **média dos 3 meses anteriores** por categoria (fecha o ponto em aberto da doc 03); (d) categoria **pode ser renomeada**; (e) tipo de ganho em **lista fixa**; (f) **importação do holerite em PDF**, com lançamento manual de reserva, PDF guardado no banco, datas informadas na importação, adiantamento sem demonstrativo próprio; (g) **FGTS e previdência como contas** de tipos novos, somadas em `bloqueado`, fora da posição geral (muda o consolidado de `contas`); (h) vale-transporte e afins viram **gasto em folha**; (i) recorrentes e horizonte continuam na fase 6; PLR adiado. Informado pelo usuário durante a escrita: adiantamento dia 15 e líquido no último dia do mês, antecipando quando não for dia útil (RN18, P5); datas da importação viram opcionais |
| 1.1 | 2026-11-14 | Regra do usuário: a empresa contribui o mesmo valor da **previdência básica** (total = 2 × básica); a voluntária não tem contrapartida. Básica e voluntária guardadas **separadas e como vieram no holerite**; contrapartida **calculada** por um multiplicador no catálogo (RF33, RF35, RF39, RF40, nova RN19) |
| 1.2 | 2026-11-14 | Pedido do usuário: a **contrapartida** vira o **terceiro campo gravado** da previdência em cada holerite, calculado na importação pela regra **vigente na competência** (hoje 100% da básica). Regra de contrapartida com histórico de vigência (RF35a) e correção por holerite (RF35b); regra nova **não reprocessa** holerites antigos, para manter os cálculos de cada mês e rastrear a mudança (RN13, RN19). Sai o multiplicador do catálogo de rubricas (RF35) |
| 1.3 | 2026-11-14 | P1–P5 aprovadas como recomendado. Status aprovado |
