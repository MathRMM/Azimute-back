# 08 — Módulo `saude`

## Escopo

Guarda as medidas do corpo e os sinais que vêm do relógio, e mostra a **tendência** de cada um. Também cuida dos lembretes de saúde que o plano de treino pede.

Até a fase 16 (Health Connect), tudo é registro manual. O modelo já nasce preparado para receber os dados automáticos.

O que ele **não** faz: diagnóstico, meta de peso agressiva, contagem de calorias ou diário alimentar.

---

## O que guarda

| Medida | Origem | Frequência | Como aparece |
|---|---|---|---|
| Peso | Manual; depois Health Connect (balança ou Samsung Health) | 1× por semana | Média móvel de 7 dias e tendência de 4 semanas |
| Cintura | Manual | 1× por mês | Valor do mês e diferença para o mês anterior |
| FC de repouso | Health Connect (fase 16) | Diária | Tendência, que costuma cair conforme o condicionamento melhora |
| Passos | Health Connect (fase 16) | Diária | Média da semana |
| Check-up e teste ergométrico | Manual | Uma vez, depois anual | Feito / pendente, com data |

O plano de treino diz para pesar e medir a cintura **sempre no mesmo dia da semana, de manhã e em jejum**. O app lembra nesse dia e nesse horário.

---

## Regras

- **Tendência, não o número do dia.** Peso varia 1 a 2 kg de um dia pro outro por água e intestino. A tela principal mostra a média móvel; o valor cru fica num detalhe.
- **Tom neutro.** Sem alarme e sem cobrança por variação: só os dados e a direção da curva.
- **Duas fontes, um valor.** Se o mesmo peso chega do Health Connect e da digitação manual no mesmo dia, vale o do Health Connect e o manual vira observação.
- **Check-up libera a Fase 2.** O módulo treino (documento 07) consulta aqui se o check-up foi feito antes de entrar na semana 5.

---

## Modelo de dados

Schema `saude`:

```sql
CREATE TABLE saude.medicoes (
    id                 UUID PRIMARY KEY,     -- vem do cliente (registro manual offline)
    tipo               TEXT NOT NULL CHECK (tipo IN
                       ('PESO','CINTURA','FC_REPOUSO','PASSOS_DIA')),
                       -- SONO e ENERGIA entram como evolução
    valor              NUMERIC(10,2) NOT NULL,
    unidade            TEXT NOT NULL,
    medido_em          TIMESTAMPTZ NOT NULL,
    origem             TEXT NOT NULL CHECK (origem IN ('MANUAL','HEALTH_CONNECT')),
    health_connect_id  TEXT UNIQUE,
    observacao         TEXT
);

CREATE TABLE saude.marcos_saude (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    tipo          TEXT NOT NULL CHECK (tipo IN ('CHECKUP','ERGOMETRICO')),
    realizado_em  DATE,
    proximo_em    DATE,
    observacao    TEXT
);

CREATE INDEX ON saude.medicoes (tipo, medido_em DESC);
```

A média móvel e a tendência são calculadas no backend, numa view ou numa consulta, e não ficam gravadas. Mesmo princípio do saldo da reserva: valor derivado gravado é valor que pode divergir.

---

## Endpoints

```
GET   /api/saude/resumo               últimas tendências de cada medida
GET   /api/saude/medicoes?tipo=PESO   série para o gráfico
PUT   /api/saude/medicoes/{id}        registro manual (ID do cliente, idempotente)
POST  /api/saude/marcos               check-up feito
```

Eventos publicados: `MedicaoRegistrada`, `CheckupRegistrado`.

---

## Lembretes

- Pesagem semanal e cintura mensal, no dia e horário combinados (notificação local, ver `00-arquitetura.md`).
- Check-up e ergométrico antes da semana 5 do treino, e depois uma vez por ano.
- Quando o módulo agenda existir (documento 11, fase 14), esses lembretes viram eventos no Google Agenda.

---

## Evoluções

- **Sono:** o Health Connect já entrega sessões de sono. É ler mais um tipo e mostrar horas por noite e a média da semana.
- **Check-in de energia (1 a 5):** uma pergunta de manhã, que vira missão do dia no módulo sistema.
- **Correlações:** com uns 2 meses de dados, cruzar sono × treinos feitos × gastos, por exemplo: "nas semanas com menos de 6 h de sono, você fez 40% menos treinos".

---

## Privacidade

Dados de saúde ficam no seu servidor, que agora é público. Por isso: disco criptografado se o provedor oferecer, backup criptografado (ver `00-arquitetura.md`) e nenhum dado de saúde individual enviado à API do Claude — a revisão da semana manda só a tendência agregada.

---

## Fora do escopo

- Calorias, macros e diário alimentar
- Meta de peso com data
- Qualquer interpretação médica dos dados
