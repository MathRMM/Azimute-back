# 07 — Módulo `treino`

## Escopo

Transforma o plano de 24 semanas em dados e compara o **planejado** com o **feito**. O módulo funciona sem relógio: o registro manual vem primeiro, e o Health Connect (documento 09, fase 16) entra depois para tirar esse trabalho.

O que ele **não** faz: gerar plano de treino sozinho, ajustar carga por IA ou prescrever intensidade. O plano vem pronto e muda só quando você decide.

O plano original, com o texto completo e as orientações, está no documento **Plano de Treino Triathlon – Corrida e Pedal do Zero**.

---

## O plano atual

**Meta final:** "triathlon sem nado", 20 km de pedal + 5 km de corrida na sequência, na semana 24. Ponto de partida: sedentarismo e sobrepeso, então tudo começa do zero.

| Fase | Semanas | Objetivo | Marco |
|---|---|---|---|
| 1 — Base | 1 a 4 | Caminhada + pedal leve (PSE 3–4) | Meta 1: caminhar 30 min contínuos e pedalar 35 min |
| 2 — Caminhada + corrida | 5 a 10 | Corrida em blocos curtos (corre/caminha) | Meta 2: 4× (5 min correndo / 1:30 caminhando) e 60 min de pedal |
| 3 — Corrida contínua | 11 a 16 | Emendar os blocos até correr sem parar | Meta 3: 25 min de corrida contínua e 75 min de pedal |
| 4 — Específico | 17 a 24 | Distância e transição bike → corrida (brick) | Meta 4: prova simulada, 20 km + 5 km |

**Estrutura da semana:** 2 corridas (A e B), 2 pedais (A e B) e, a partir da semana 3, 2 sessões de força de 15 min. Uma 3ª corrida curta e opcional pode entrar a partir da semana 17.

**Semanas especiais:** alívio nas semanas 4, 8, 12, 16 e 20; polimento na 23; prova na 24.

**Testes de evolução:**

| Teste | Semanas | O que medir |
|---|---|---|
| Corrida/caminhada | 1, 8, 16 e 24 | Distância em 20 min no ritmo mais forte que você sustenta confortável |
| Pedal | 2, 9, 17 e 23 | Distância em 30 min em ritmo moderado |
| Peso e cintura | 1× por mês | Mesmo dia da semana, de manhã, em jejum (fica no módulo saúde, documento 08) |

**Intensidade:** escala de esforço percebido (PSE) de 0 a 10 e teste da fala.

---

## Modelo de dados

Schema `treino`:

```sql
-- Um plano é versionado, nunca editado (mesma regra de reserva.planos)
CREATE TABLE treino.planos (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    nome            TEXT NOT NULL,
    versao          INT NOT NULL,
    inicio          DATE NOT NULL CHECK (EXTRACT(ISODOW FROM inicio) = 1),  -- segunda
    semanas         INT NOT NULL CHECK (semanas > 0),
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    substituido_por UUID REFERENCES treino.planos(id)
);

CREATE TABLE treino.semanas (
    plano_id  UUID NOT NULL REFERENCES treino.planos(id),
    numero    INT NOT NULL,
    fase      INT NOT NULL,
    tipo      TEXT NOT NULL CHECK (tipo IN ('NORMAL','ALIVIO','POLIMENTO','PROVA')),
    PRIMARY KEY (plano_id, numero)
);

CREATE TABLE treino.sessoes_planejadas (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    plano_id      UUID NOT NULL REFERENCES treino.planos(id),
    semana        INT NOT NULL,
    ordem         INT NOT NULL,
    modalidade    TEXT NOT NULL CHECK (modalidade IN
                  ('CORRIDA','PEDAL','FORCA','BRICK','TESTE')),
    rotulo        TEXT NOT NULL,          -- 'Corrida A', 'Pedal B', 'Força 1'
    descricao     TEXT NOT NULL,          -- '8× (1 min corre / 2 min caminha)'
    estrutura     JSONB,                  -- blocos: [{tipo, duracao_s, intensidade}]
    duracao_min   INT NOT NULL,
    pse_alvo_min  INT, pse_alvo_max INT,
    marco         TEXT,                   -- 'Meta 2', se a sessão fecha um marco
    opcional      BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE treino.sessoes_realizadas (
    id                 UUID PRIMARY KEY,  -- vem do cliente (registro manual offline)
    planejada_id       UUID REFERENCES treino.sessoes_planejadas(id),  -- NULL = fora do plano
    inicio             TIMESTAMPTZ NOT NULL,
    fim                TIMESTAMPTZ NOT NULL,
    modalidade         TEXT NOT NULL,
    duracao_min        INT NOT NULL,
    distancia_m        INT,
    fc_media           INT,
    fc_max             INT,
    pse                INT CHECK (pse BETWEEN 0 AND 10),   -- só você sabe; o relógio não
    origem             TEXT NOT NULL CHECK (origem IN ('MANUAL','HEALTH_CONNECT')),
    health_connect_id  TEXT UNIQUE,
    observacao         TEXT
);

CREATE TABLE treino.ausencias (
    data    DATE PRIMARY KEY,
    motivo  TEXT NOT NULL CHECK (motivo IN
            ('DESCANSO_PLANEJADO','DOR','DOENCA','VIAGEM','OUTRO'))
);

CREATE TABLE treino.testes (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    tipo          TEXT NOT NULL CHECK (tipo IN ('CORRIDA_20MIN','PEDAL_30MIN')),
    semana        INT NOT NULL,
    resultado_m   INT NOT NULL,
    realizado_em  DATE NOT NULL
);

CREATE TRIGGER planos_treino_imutaveis
    BEFORE UPDATE OR DELETE ON treino.planos
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();
```

O trigger em `treino.planos` bloqueia também o `UPDATE` de `substituido_por`. Para marcar a substituição, a versão nova aponta para a anterior por uma tabela de ligação, ou `substituido_por` sai do plano e vira consulta pela maior versão. Decidir na implementação; o importante é que plano gravado não muda.

O plano vira dados por um **seed** gerado a partir das tabelas do documento original. Mudar o plano cria uma versão nova; as sessões já feitas continuam apontando para a versão antiga.

---

## Regras

- **A sessão é da semana, não de um dia fixo.** "Corrida A" está cumprida se foi feita em qualquer dia daquela semana. Isso aguenta semana corrida sem virar falha.
- **Vínculo automático.** Um treino registrado (manual ou, na fase 16, do Health Connect) se liga à primeira sessão planejada pendente da mesma semana e modalidade, se a duração for de pelo menos **80% da planejada** (valor configurável). Abaixo disso, fica como "fora do plano" e você pode vincular à mão.
- **PSE é manual.** Depois de um treino, o app pede a nota de esforço com um toque. Sem a nota, o treino conta igual.
- **Dor não é falha.** Uma ausência por `DOR` ou `DOENCA` sai do cálculo de consistência (documento 10). O plano diz "na dúvida entre treinar ou descansar, descanse", e o app não pode punir isso.
- **Semanas de alívio contam normalmente.** São mais leves, mas são parte do plano.
- **Avanço de fase não é automático pela data.** Se uma semana ficou bem abaixo do planejado, o app sugere repeti-la em vez de seguir. Você decide.

---

## Segurança

Regras que vêm do plano original e o app precisa refletir:

- **Check-up antes de aumentar a intensidade:** o módulo saúde guarda se o check-up e o teste ergométrico foram feitos. Sem isso, o app avisa ao entrar na Fase 2 (semana 5), que é quando a corrida começa.
- **Sinais de alerta:** dor ou aperto no peito, tontura, falta de ar fora do normal, palpitação ou desmaio. A tela de registro tem um atalho "senti algo estranho" que mostra a orientação de parar e procurar atendimento.
- **Dor articular ou óssea** que piora no treino ou dura mais de 3 dias: o app sugere trocar a corrida pelo pedal e marca a observação.

---

## Endpoints

```
GET    /api/treino/semana-atual        sessões da semana com status
GET    /api/treino/progresso           fases, marcos, testes
PUT    /api/treino/sessoes/{id}        registro manual (ID do cliente, idempotente)
PATCH  /api/treino/sessoes/{id}        PSE, observação, vínculo
PUT    /api/treino/ausencias/{data}    descanso, dor, viagem
POST   /api/treino/testes              resultado de teste
```

Eventos publicados: `SessaoRealizada`, `MarcoAtingido`, `AusenciaRegistrada`. O módulo sistema (documento 10) escuta todos eles.

---

## Telas

- **Semana:** as 4 a 6 sessões da semana em cartões, com status (pendente, feita, fora do plano) e a descrição do treino.
- **Registro rápido:** modalidade, duração e PSE. Três toques. Funciona offline.
- **Progresso:** linha do tempo das 24 semanas com as fases, os marcos e os testes lado a lado.

---

## Evoluções

- **3ª corrida opcional** a partir da semana 17 (o modelo já tem `opcional`).
- **Duathlon sprint** depois da semana 24 (5 km corrida / 20 km pedal / 2,5 km corrida): é só um plano novo.
- **Natação**, quando houver piscina: uma modalidade a mais.
- **Treinos na agenda:** cada sessão da semana vira um evento, e a secretária (documento 11) protege esse horário.
- **GPS próprio:** o app grava o trajeto durante o treino (ver documento 09).

---

## Testes

- Unit: vínculo automático (80%, modalidade, semana), status da semana, sugestão de repetir semana.
- Integração: unicidade de `health_connect_id`; trigger de imutabilidade em `treino.planos`; `inicio` fora da segunda é rejeitado.
- E2E: registrar uma sessão manual e ver a semana atualizada; enviar a mesma sessão duas vezes e continuar com um registro só.
