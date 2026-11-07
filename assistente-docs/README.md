# Assistente pessoal — índice

Assistente do dia a dia, usuário único: **contador** (finanças), **treinador** (treino e saúde) e **secretária** (agenda), com o **Sistema** por cima entregando missões do dia e consistência. Backend Java 25 + Spring Boot + PostgreSQL 18 concentra a regra de negócio, numa VPS exposta na internet; front em Kotlin (Android) é interface.

## Documentos (ler nesta ordem)

| # | Arquivo | Conteúdo |
|---|---|---|
| 00 | `00-arquitetura.md` | **Comece aqui.** Stack, monólito modular, Spring Modulith e eventos, kernel, as três camadas, triggers, cliente offline, integrações, jobs, segurança e implantação, ordem de construção, decisões registradas |
| 01 | `01-modulo-auth.md` | Senha + token por dispositivo, limite de tentativas, credenciais de integrações, OAuth do Google |
| 02 | `02-modulo-calculadora.md` | Juros compostos, taxa implícita por bisseção, comparação à vista × financiado, sensibilidade à entrada, histórico de propostas |
| 03 | `03-modulo-fluxo.md` | Ganhos bruto/líquido, gastos, categorias, recorrentes, horizonte de 12 meses |
| 04 | `04-modulo-cartoes.md` | Cartões, fechamento, parcelamento, dia útil e feriados, composição de fatura, limite |
| 05 | `05-modulo-reserva.md` | Aportes, planos versionados, motor de distribuição, projeção, metas |
| 06 | `06-modulo-contas.md` | Contas reais, movimentações, conciliação com extrato, saldo projetado |
| 07 | `07-modulo-treino.md` | Plano de 24 semanas como dados, sessões planejadas × feitas |
| 08 | `08-modulo-saude.md` | Peso, medidas, sinais do relógio, lembretes de saúde |
| 09 | `09-integracao-health-connect.md` | Dados do relógio até o backend (**adiado para a fase 16**) |
| 10 | `10-modulo-sistema.md` | Missões do dia, consistência e revisão da semana |
| 11 | `11-modulo-agenda.md` | Secretária com Google Agenda, vencimentos e captura rápida |

### Prompts para o Claude Design (`prompts/`)

| Arquivo | Tela |
|---|---|
| `prompt-claude-design.md` | Painel de reserva, projeção e calculadora |
| `prompt-fluxo-gastos.md` | Ganhos, gastos e cartões de crédito |
| `prompt-simulador-entrada.md` | Controle deslizante de entrada × parcela |

Os protótipos saem em React; servem de referência visual para o app Kotlin.

### Fora deste pacote

- `financas-br.skill` — skill de cálculo com a matemática, o plano de reserva e as propostas analisadas. Gerada na conversa da calculadora, com botão de instalar no card de lá.
- **Plano de Treino Triathlon – Corrida e Pedal do Zero** — o plano completo, em documento próprio. O `07` resume e modela ele.
- `arquitetura-kotlin.md` — primeira versão (Kotlin + Ktor + SQLite). **Obsoleta**, substituída pelos documentos 00 a 06.

---

## Ordem de construção

| Fase | Módulo | Por quê nessa posição |
|---|---|---|
| 1 | `comum` + `calculadora core` | Zero dependência. Entregável isolado — já serve numa concessionária. Já nasce com Spring Modulith e fila offline genérica |
| 2 | `auth` | Depois vira retrabalho em todos os controllers; e o backend é público |
| 3 | `contas` | Fundacional. Se vier depois de `fluxo`, exige migração de dados |
| 4 | `fluxo` | Ganhos e gastos já nascem vinculados a conta |
| 5 | `cartoes` | Mais complexo; precisa de `fluxo` e `contas` |
| 6 | `fluxo` parte 2 | Recorrentes e horizonte — precisa de cartões |
| 7 | `contas` parte 2 | Saldo projetado — precisa saber quais faturas debitam cada conta |
| 8 | `reserva` | Independente do resto, exceto conta de destino |
| 9 | `treino` (manual) | Plano pronto; funciona sem relógio |
| 10 | `saude` (manual) | Peso e cintura digitados |
| 11 | `sistema`: missões + consistência | Precisa de finanças e treino gerando eventos |
| 12 | `sistema`: revisão da semana | Precisa de algumas semanas de dados |
| 13 | `agenda`: secretária | Última área por prioridade |
| 14 | `agenda`: vencimentos | Liga cartões, fluxo e saúde à agenda |
| 15 | `agenda`: captura rápida | Porta de entrada da secretária |
| 16 | `integracao.healthconnect` | Quando houver smartwatch |

**Não pule a fase 1 para "começar pelo que importa".** Ela define `Dinheiro`, `Competencia` e `Percentual`, que todo o resto usa, monta a base de eventos e a fila offline, e entrega algo utilizável no mesmo dia.

---

## Primeiros passos concretos

1. **Verificar o baseline de Java do Spring Boot e do Spring Modulith.** A compatibilidade com Java 25 varia entre linhas e a informação muda rápido. Confirmar na documentação oficial antes de fixar versões no `build.gradle.kts`.

2. **Subir Postgres 18 e testar `uuidv7()`:**
   ```sql
   SELECT uuidv7();
   ```
   Se a função não existir na sua instalação, gerar os IDs na aplicação — mas preferir o banco.

3. **Criar a estrutura de pacotes por módulo**, não por camada:
   ```
   br.dev.assistente
   ├── comum/
   ├── auth/
   ├── calculadora/{core,historico}/
   ├── contas/{domain,application,infra,api}/
   ├── fluxo/
   ├── cartoes/
   ├── reserva/
   ├── treino/   saude/   sistema/   agenda/
   └── integracao/{claude,google,healthconnect}/
   ```
   Os módulos das fases 9 em diante podem nascer como pacotes vazios, para o Spring Modulith já verificar as fronteiras.

4. **Migration V001** com a função de imutabilidade, que os módulos seguintes referenciam:
   ```sql
   CREATE FUNCTION impedir_alteracao() RETURNS TRIGGER AS $$
   BEGIN
       RAISE EXCEPTION 'Registro imutável em %: crie uma versão nova', TG_TABLE_NAME;
   END;
   $$ LANGUAGE plpgsql;
   ```

5. **Fixar o fuso** em `America/Sao_Paulo` na aplicação e no container. VPS vem em UTC; deixar para depois produz lançamento de 22h caindo no dia seguinte.

6. **Escrever `Dinheiro` e seus testes antes de qualquer outra coisa.** `BigDecimal`, escala 2, `HALF_EVEN`. É o tipo mais usado do sistema.

7. **Antes de expor o backend** (fim da fase 2): Caddy com HTTPS, firewall só com 443/80, Postgres sem porta pública, `APP_SENHA_INICIAL` removida depois do primeiro boot, backup diário fora da VPS. Checklist completo em `00-arquitetura.md` → Implantação.

---

## Invariantes que devem virar teste de propriedade

Estas valem mais que qualquer conjunto de exemplos. Rodar com valores aleatórios:

| Módulo | Invariante |
|---|---|
| `calculadora` | `taxaImplicita(pv, parcela(pv, i, n), n) ≈ i` para qualquer entrada válida |
| `cartoes` | A soma das parcelas geradas é exatamente igual ao valor da compra, para qualquer valor e qualquer N |
| `reserva` | A soma das fatias mais o excedente não alocado é exatamente igual ao aporte, para qualquer plano e quaisquer saldos |
| `fluxo` | Gastos à vista + parcelas + recorrentes de uma competência = comprometido total daquele mês |
| `contas` | Transferência não altera o patrimônio consolidado |
| `sistema` | O mesmo evento entregue N vezes cumpre a missão uma vez só |
| Toda criação offline | O mesmo `PUT /{id}` enviado N vezes resulta em um registro |

---

## Armadilhas já identificadas

| Armadilha | Onde está documentada |
|---|---|
| `double` para dinheiro | 00 — kernel |
| Somar saldo de conta com saldo de categoria de reserva | 06 — as duas camadas |
| Tratar transferência ou pagamento de fatura como gasto | 06 — contagem dupla |
| Recalcular a curva planejada de uma meta | 05 — planejado × real |
| Recalcular valores de simulação salva | 02 — snapshot |
| Compra após o fechamento caindo na fatura errada | 04 — regra 1 |
| Mirar a data de vencimento adiada em vez da nominal | 04 — regra 3 |
| Diluir assinatura anual em doze avos | 03 — regra da anuidade |
| Saldo como soma acumulada pura | 06 — conciliação |
| Regra de negócio volátil em trigger | 00 — a terceira camada |
| Aporte automático a partir da sobra (inclusive pela missão do Sistema) | 00 — regra 6; 10 — missões |
| Sequência de dias que zera e pune descanso | 10 — consistência |
| Deixar a IA escolher horário ou gravar sem confirmação | 11 — fluxo da secretária |
| `requiresSecure()` atrás do proxy sem `forward-headers-strategy` | 01 — configuração |
| Backup na mesma máquina do banco | 00 — implantação |
| Servidor em UTC | 00 — fuso horário |

---

## O que mudou desde a versão de setembro

Os documentos 00 a 06 foram revisados para o assistente. O que muda em relação à versão entregue na conversa da calculadora:

- **Escopo:** de finanças pessoais para assistente (treino, saúde, sistema, agenda). Pacote raiz `br.dev.reserva` → `br.dev.assistente`.
- **Backend exposto na internet:** auth passa de HTTP Basic para senha + token por dispositivo, com limite de tentativas; nova seção de implantação (Caddy, firewall, backup).
- **Spring Modulith obrigatório**, com eventos persistidos; cada módulo financeiro publica eventos e expõe consultas novas para o Sistema e a agenda.
- **ID gerado no cliente** deixou de ser exceção da calculadora e vale para tudo que nasce offline, inclusive gastos e ganhos (`PUT /{id}`).
- **Fila offline** virou componente compartilhado do app; **notificações locais** no Android.
- **Taxas anuais de referência** corrigidas para 51,3% e 62,6% (antes 51,4% e 62,7%, por arredondamento intermediário).
- **Ganho ganhou conta de destino**; recorrentes materializados ganharam chave de idempotência; conciliações e simulações ganharam seus triggers nos próprios schemas.
- **Decisões registradas** vão agora de 1 a 24; ordem de construção vai de 1 a 16.

---

## Perguntas em aberto

| Pergunta | Afeta | Decidir antes da fase |
|---|---|---|
| Orçamento por categoria no `fluxo`, ou comparar com a média dos 3 meses? | 03, 10 | 4 |
| Nome do pacote: `br.dev.assistente` ou outro? | 00 | 1 |
| Qual VPS e qual domínio? | 00, 01 | 2 |
| Horário da revisão da semana (sugestão: domingo, 20h) | 10 | 12 |
| Horários de trabalho e sono; calendário separado "Assistente"? | 11 | 13 |
| Qual smartwatch? | 09 | 16 |
| Quanto a moto economiza ou custa por mês (fecha a análise de antecipação) | 02 | — |
