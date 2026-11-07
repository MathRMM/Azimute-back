# 11 — Módulo `agenda`

## Escopo

A **secretária**: você diz o que precisa fazer, a IA encontra um horário livre no Google Agenda e cria o evento, para você dificilmente perder alguma coisa. Inclui também os **vencimentos** vindos das finanças e a **captura rápida**.

É o último bloco da ordem de construção (fases 13 a 15), por decisão de prioridade: financeiro e treino vêm antes.

---

## Fluxo da secretária

```
"preciso ir ao banco essa semana, leva 1h"
        │
        ▼
Claude (API, tool use) extrai:
  título: Ir ao banco · duração: 60 min · prazo: sexta
  prioridade: normal · restrição: horário comercial
        │
        ▼
Backend consulta o Google Agenda (freeBusy) até o prazo
        │
        ▼
Aplica as regras (trabalho, sono, treino, folga entre eventos)
        │
        ▼
Propõe 2 ou 3 horários  ──▶  você escolhe  ──▶  evento criado
```

**A IA só interpreta o pedido.** Quem procura o horário é código determinístico no backend: os horários livres vêm da API do Google, não de um palpite da IA. Isso deixa a busca testável e evita evento criado em cima de outro.

**Confirmação sempre, no começo.** Nenhum evento é criado sem você tocar num dos horários propostos. Depois de um tempo, dá pra liberar o agendamento automático para pedidos simples.

---

## Regras de encaixe

- **Horários proibidos:** trabalho e sono, configuráveis por dia da semana.
- **Treino protegido:** quando os treinos virarem eventos (evolução do documento 07), a secretária não encaixa nada em cima deles.
- **Folga entre eventos:** 15 min por padrão, para deslocamento.
- **Preferência pelo mais cedo:** dentro do prazo, propõe primeiro os horários mais próximos.
- **Sem horário até o prazo:** a secretária avisa e oferece mover outra coisa ou estender o prazo. Nunca encaixa em horário proibido sem perguntar.

---

## Integração com o Google Agenda

- **Autorização:** fluxo OAuth de servidor, com callback no backend público. O passo a passo e a guarda do refresh token (criptografado) estão em `01-modulo-auth.md`.
- **Escopos mínimos:** ler horários ocupados (freeBusy) e criar e editar eventos. Sem acesso a outros dados da conta. Confirmar os nomes exatos dos escopos na documentação do Google na fase 13.
- **Calendário próprio (sugestão):** os eventos criados pelo assistente vão para um calendário "Assistente" dentro da sua conta. Fica fácil ver, filtrar ou apagar tudo de uma vez.
- **Rastreio:** cada evento criado guarda o `google_event_id`. Se você mover o evento no Google Agenda, o backend percebe na próxima leitura (job a cada 30 min) e atualiza.
- **Interface, não cliente direto:** a `agenda` depende de `CalendarioExterno`; quem implementa é `integracao.google`. Nos testes, uma implementação falsa.

---

## Vencimentos

As finanças já sabem as datas; a agenda só lembra.

| Origem | Exemplo | Lembrete |
|---|---|---|
| Cartões (04), `proximosVencimentos` | Vencimento da fatura, **na data nominal** | 3 dias antes e no dia |
| Fluxo (03), `recorrentesPrevistos` | Assinaturas, contas fixas | No dia da cobrança |
| Manual | IPVA, licenciamento, revisão da bike | Data configurável |
| Saúde (08) | Check-up anual, pesagem semanal | Conforme o módulo |

O vencimento vira evento de dia inteiro no calendário "Assistente" e também missão do dia no módulo sistema (documento 10). Quando chega `FaturaPaga`, o evento da fatura é marcado como concluído.

---

## Captura rápida

Um botão (ou comando de voz) para jogar qualquer coisa sem pensar onde ela vai. A IA classifica depois:

| Você diz | Vira |
|---|---|
| "Preciso renovar a CNH até março" | Pedido para a secretária |
| "Gastei 40 no mercado" | Gasto no fluxo (03), com confirmação |
| "Ideia: comprar rolo de treino" | Nota na caixa de entrada |

Tudo cai primeiro numa **caixa de entrada**. Nada é gravado em outro módulo sem você confirmar, porque um gasto lançado errado bagunça as finanças. A captura funciona offline (fila `sync`); a classificação acontece quando sincronizar.

---

## Modelo de dados

Schema `agenda`:

```sql
CREATE TABLE agenda.preferencias (
    dia_semana       INT PRIMARY KEY CHECK (dia_semana BETWEEN 1 AND 7),
    inicio_trabalho  TIME, fim_trabalho TIME,
    inicio_sono      TIME, fim_sono     TIME,
    folga_min        INT NOT NULL DEFAULT 15
);

CREATE TABLE agenda.pedidos (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    texto_original  TEXT NOT NULL,
    titulo          TEXT, duracao_min INT, prazo TIMESTAMPTZ, prioridade TEXT,
    status          TEXT NOT NULL CHECK (status IN
                    ('INTERPRETADO','PROPOSTO','AGENDADO','SEM_HORARIO','CANCELADO')),
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agenda.propostas (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    pedido_id  UUID NOT NULL REFERENCES agenda.pedidos(id),
    inicio     TIMESTAMPTZ NOT NULL,
    fim        TIMESTAMPTZ NOT NULL,
    escolhida  BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE agenda.eventos (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    pedido_id        UUID REFERENCES agenda.pedidos(id),
    origem           TEXT NOT NULL CHECK (origem IN
                     ('SECRETARIA','VENCIMENTO','TREINO','SAUDE')),
    origem_ref       TEXT,             -- ex.: cartão + competência, para não duplicar vencimento
    google_event_id  TEXT UNIQUE,
    inicio           TIMESTAMPTZ NOT NULL,
    fim              TIMESTAMPTZ NOT NULL,
    titulo           TEXT NOT NULL,
    UNIQUE (origem, origem_ref)
);

CREATE TABLE agenda.entrada (
    id              UUID PRIMARY KEY,   -- vem do cliente (captura offline)
    texto           TEXT NOT NULL,
    classificacao   TEXT,
    destino_id      TEXT,
    status          TEXT NOT NULL CHECK (status IN ('NOVO','CONFIRMADO','DESCARTADO')),
    criado_em       TIMESTAMPTZ NOT NULL
);
```

`UNIQUE (origem, origem_ref)` torna o job de vencimentos idempotente: a mesma fatura não vira dois eventos.

---

## Endpoints

```
POST  /api/agenda/pedidos                  { texto } → interpretação + propostas
POST  /api/agenda/pedidos/{id}/escolher    { propostaId } → cria o evento
GET   /api/agenda/proximos                 próximos compromissos
PUT   /api/agenda/entrada/{id}             captura rápida (ID do cliente, idempotente)
POST  /api/agenda/entrada/{id}/confirmar
PUT   /api/agenda/preferencias
```

Eventos publicados: `EventoAgendado`, `EventoConcluido`.

---

## Testes

- Unit: busca de horário (janelas ocupadas, horários proibidos, folga, prazo sem espaço).
- Integração: Google Agenda simulado; `google_event_id` único; vencimento da mesma fatura não duplica.
- E2E: pedido em texto → propostas → escolha → evento criado; pedido sem horário até o prazo → `SEM_HORARIO`.
- Interpretação da IA: um conjunto fixo de frases de exemplo com o resultado esperado, rodado a cada mudança de prompt.

---

## Perguntas em aberto

- Quais são seus horários de trabalho e de sono?
- Prefere o calendário separado "Assistente" ou tudo no calendário principal?
- Depois de quanto tempo de uso você liberaria o agendamento sem confirmação?
