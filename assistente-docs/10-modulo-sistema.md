# 10 — Módulo `sistema`

## Escopo

O "Sistema" é a camada que junta tudo: entrega as **missões do dia**, mostra a **consistência** de cada área e faz a **revisão da semana**. Ele não tem regra de negócio própria sobre dinheiro, treino ou agenda: só lê o que os outros módulos dizem.

---

## O que ficou de fora, e por quê

| Ideia original | Decisão | Motivo |
|---|---|---|
| XP e níveis | Removido | Número inventado. A consistência é o que dá pra ver na vida real. |
| Ouro / moeda | Removido | O dinheiro real já é a métrica. |
| Atributos (Força, Disciplina...) | Substituído por áreas | Treino, finanças e compromissos medidos com dados reais. |
| Penalidade por missão perdida | Removido | Sem XP não há o que tirar. A falha aparece no mapa de consistência, e isso basta. |
| Sequência de dias | Substituído por adesão | Uma falha não zera nada; descanso faz parte do plano. |

---

## Missões do dia

Geradas toda madrugada, para o dia, a partir dos outros módulos:

| Área | De onde vem | Exemplo | Como se cumpre |
|---|---|---|---|
| Treino | Sessões pendentes da semana (07) | "Pedal A: 30 min leve" | Automático quando chega `SessaoRealizada` |
| Treino | Força da semana (07) | "Força: circuito de 15 min" | Automático ou com um toque |
| Finanças | Fluxo (03) | "Lançar os gastos de ontem" | Automático quando chega `GastoLancado` |
| Finanças | Reserva (05) + fluxo (03) | "Aporte do mês (sobra prevista: R$ X)" | Automático quando chega `AporteFeito` |
| Finanças | Cartões (04) | "Fatura do cartão vence em 3 dias" | Automático quando chega `FaturaPaga` |
| Saúde | Saúde (08) | "Pesagem semanal" | Automático quando chega `MedicaoRegistrada` |
| Compromissos | Agenda (11) | "Banco, 14h" | Com um toque, ou automático quando o evento passa |

**Regras:**

- **Poucas missões.** No máximo 5 ou 6 por dia. Se tiver mais, entram as de prazo mais curto e o resto fica visível numa lista de "também pendente".
- **O máximo possível se cumpre sozinho.** Você só toca no que o app não tem como saber.
- **Treino flexível.** Como as sessões são da semana (documento 07), o Sistema sugere a sessão do dia, mas fazê-la em outro dia da mesma semana conta igual.
- **Dispensar é diferente de falhar.** Uma missão pode ser dispensada com motivo (descanso, dor, viagem). Dispensada com motivo não conta contra a consistência.
- **Aporte é lembrete, não ação.** A missão sugere um valor, mas quem registra o aporte é você (regra 6 do `00-arquitetura.md`).

---

## Consistência

**Métrica principal:** adesão nas últimas 4 semanas, por área.

```
adesão = missões cumpridas / (missões geradas − missões dispensadas com motivo)
```

- **Por área:** treino, finanças, saúde e compromissos, cada uma com sua %. Uma média geral esconderia onde está o problema.
- **Mapa de calor:** um quadrado por dia, como o do GitHub, colorido pela % cumprida naquele dia. Toque num dia para ver as missões dele.
- **Treino olha a semana:** a adesão de treino é calculada por semana (sessões feitas / planejadas), não por dia, pelo mesmo motivo da regra acima.
- **Referência, não meta rígida:** 80% de adesão em 4 semanas já é um resultado muito bom. A tela mostra isso como linha de referência.

---

## Revisão da semana

Toda semana (sugestão: domingo às 20h), uma tela única com:

| Bloco | Conteúdo |
|---|---|
| Consistência | % por área na semana e nas últimas 4 semanas |
| Dinheiro | Gasto do mês × média dos 3 meses anteriores (até decidir orçamento, ver `03-modulo-fluxo.md`), reserva × meta, próxima fatura |
| Treino | Sessões feitas, próximo marco, resultado de teste se houver |
| Saúde | Tendência do peso (e da FC de repouso, a partir da fase 16) |
| Próxima semana | O que está planejado: fase do treino, vencimentos, compromissos |

No fim, um parágrafo curto escrito pela IA (Claude via API, pelo adaptador `integracao.claude`) a partir desses números: o que foi bem, o que escorregou e uma sugestão para a próxima semana. A IA recebe só os números agregados, nunca os dados brutos, e não inventa nada que não esteja ali. Se a API falhar ou o teto mensal de gasto tiver sido atingido, a revisão sai sem o parágrafo.

---

## Arquitetura

O `sistema` **só consome**: nenhum módulo depende dele.

- Os outros módulos publicam eventos de aplicação: `SessaoRealizada`, `AusenciaRegistrada`, `MarcoAtingido`, `GastoLancado`, `AporteFeito`, `FaturaPaga`, `MedicaoRegistrada`, `EventoAgendado`.
- O `sistema` escuta (Spring Modulith, eventos persistidos) e marca as missões como cumpridas.
- A geração diária consulta os outros módulos pelas suas fachadas públicas (`proximosVencimentos`, `resumoDoMes`, `aporteRegistrado`...), nunca pelas tabelas deles.

Resultado: dá pra desligar o Sistema, ou refazê-lo do zero, sem tocar nas finanças nem no treino.

---

## Modelo de dados

Schema `sistema`:

```sql
CREATE TABLE sistema.missoes (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    data             DATE NOT NULL,
    area             TEXT NOT NULL CHECK (area IN
                     ('TREINO','FINANCAS','SAUDE','COMPROMISSOS')),
    titulo           TEXT NOT NULL,
    origem_tipo      TEXT NOT NULL,     -- de onde veio (sessão, fatura, evento...)
    origem_id        TEXT NOT NULL,
    status           TEXT NOT NULL CHECK (status IN
                     ('PENDENTE','CUMPRIDA','DISPENSADA','PERDIDA')),
    motivo_dispensa  TEXT,
    cumprida_em      TIMESTAMPTZ,
    UNIQUE (data, origem_tipo, origem_id)
);

CREATE TABLE sistema.revisoes (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    semana_inicio  DATE NOT NULL UNIQUE CHECK (EXTRACT(ISODOW FROM semana_inicio) = 1),
    numeros        JSONB NOT NULL,      -- snapshot dos números da semana
    texto_ia       TEXT,
    gerada_em      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER revisoes_imutaveis
    BEFORE UPDATE OR DELETE ON sistema.revisoes
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();
```

A revisão é um **snapshot imutável**: ela registra como a semana estava naquele momento. A consistência é calculada a partir de `sistema.missoes`, por consulta, e não fica gravada.

`UNIQUE (data, origem_tipo, origem_id)` torna a geração idempotente: o job rodando duas vezes não duplica missões.

Uma missão `PENDENTE` vira `PERDIDA` na virada do dia, exceto as de treino, que só viram `PERDIDA` na virada da semana.

---

## Endpoints

```
GET   /api/sistema/hoje                missões do dia (o app agenda notificações locais a partir disto)
POST  /api/sistema/missoes/{id}/cumprir
POST  /api/sistema/missoes/{id}/dispensar   { motivo }
GET   /api/sistema/consistencia?semanas=4
GET   /api/sistema/revisoes/{semana}
```

---

## Evoluções

- **Revisão do mês:** mesma lógica, janela maior.
- **Correlações** entre áreas, quando o módulo saúde tiver sono e energia.
- **Check-in de energia** como missão diária.
- **Missão de conciliação:** contas de giro toda semana, reserva todo mês (`ConciliacaoFeita`).
- **Push pelo FCM**, se as notificações locais não bastarem.

---

## Testes

- Unit: fórmula de adesão (com e sem dispensas), limite de missões por dia, regra de `PERDIDA` por dia e por semana.
- Integração: evento chega duas vezes e a missão é cumprida uma vez só (`UNIQUE` + idempotência); revisão imutável; evento reprocessado após reinício.
- E2E: registrar um treino e ver a missão do dia cumprida; dispensar com "dor" e ver a adesão inalterada.
