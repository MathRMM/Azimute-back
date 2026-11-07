# 09 — Integração Health Connect

> **Adiado para a fase 16, depois da agenda.** Ainda não há smartwatch. Até lá, treino e saúde funcionam com registro manual. Quando a integração chegar, o modelo de dados não muda: os campos `origem` e `health_connect_id` já estão previstos nos módulos 07 e 08.

## Escopo

Leva os dados do relógio até o backend sem você registrar nada à mão. Alimenta os módulos treino (07) e saúde (08).

---

## O que é o Health Connect

O hub de dados de saúde do Android, feito pelo Google em parceria com a Samsung. Ele junta mais de 50 tipos de dados (atividade, medidas corporais, sono, sinais vitais) vindos de apps diferentes. Os dados do Galaxy Watch, Galaxy Ring e Galaxy Fit caem no Samsung Health, e o Samsung Health sincroniza com o Health Connect.

**Ponto central: o Health Connect não tem API na nuvem.** Os dados ficam guardados no próprio celular, então só um app Android consegue ler. Isso não muda com o backend público.

---

## Fluxo

```
Relógio ──▶ Samsung Health ──▶ Health Connect ──▶ App Kotlin ──▶ Backend Java
             (Bluetooth)        (no celular)       (lê e envia)    (regra de negócio)
```

**Ativação, uma vez só:** Samsung Health → perfil → Configurações → Gerenciamento de dados → Health Connect → ligar a sincronização e marcar os tipos de dado. Depois, conceder ao seu app permissão de leitura dos mesmos tipos.

---

## Quem faz o quê

| Camada | Responsabilidade | Não faz |
|---|---|---|
| **App Kotlin** | Pede as permissões, lê do Health Connect, guarda o token de mudanças, envia lotes de dados brutos, coloca na fila quando está offline | Decidir se o treino cumpriu o plano, calcular tendência |
| **Backend** | Recebe o lote, grava sem duplicar, liga o treino à sessão planejada, calcula tendências, publica eventos | Falar com o Health Connect |
| **Banco** | Unicidade de `health_connect_id` | — |

Isso segue a regra das três camadas do `00-arquitetura.md`: o cliente é interface e coleta, a regra fica no backend e os invariantes ficam no banco.

---

## Tipos de dado

| Tipo no Health Connect | Vai para | Fase |
|---|---|---|
| `ExerciseSessionRecord` | `treino.sessoes_realizadas` | 16 |
| `HeartRateRecord` (dentro do treino) | FC média e máxima da sessão | 16 |
| `DistanceRecord` (dentro do treino) | Distância da sessão | 16 |
| `WeightRecord` | `saude.medicoes` (PESO) | 16 |
| `RestingHeartRateRecord` | `saude.medicoes` (FC_REPOUSO) | 16 |
| `StepsRecord` | `saude.medicoes` (PASSOS_DIA), agregado por dia | 16 |
| `SleepSessionRecord` | `saude.medicoes` (SONO) | Evolução |
| `ExerciseRoute` | Trajeto da sessão | Evolução (GPS) |

Cada tipo precisa da sua permissão de leitura declarada no manifest e concedida por você.

---

## Sincronização

**Só o que mudou.** O Health Connect tem um token de mudanças. Na primeira vez o app lê o histórico (por exemplo, os últimos 30 dias) e pega um token. Nas próximas, pede só o que entrou, mudou ou foi apagado desde aquele token. O token expira se ficar muito tempo sem uso (a documentação falava em 30 dias; confirmar na implementação). Nesse caso, o app refaz a leitura completa da janela, e a idempotência do backend garante que nada duplica.

**Sozinho, de tempos em tempos.** Um trabalho periódico com WorkManager, a cada poucas horas, e também ao abrir o app. Ler em segundo plano exige uma permissão a mais, que você concede uma vez.

**Offline.** Usa a fila `sync` compartilhada do app (ver `00-arquitetura.md`): o lote fica guardado no celular e sobe quando voltar a conexão.

**Apagamentos.** Se você apagar um treino no Samsung Health, o token de mudanças traz essa remoção. O app envia, e o backend apaga o registro de origem `HEALTH_CONNECT` correspondente. Registros manuais nunca são apagados por sincronização.

---

## Idempotência

O backend precisa aceitar o mesmo dado mais de uma vez sem duplicar. Cada registro do Health Connect tem um ID próprio:

```sql
health_connect_id TEXT UNIQUE NULL
```

```sql
INSERT INTO saude.medicoes (...) VALUES (...)
ON CONFLICT (health_connect_id) DO UPDATE
   SET valor = EXCLUDED.valor, medido_em = EXCLUDED.medido_em;
```

`DO UPDATE` em vez de `DO NOTHING` porque um registro pode ser corrigido na origem (por exemplo, você ajusta a duração do treino no Samsung Health).

---

## Endpoint

Autenticado com o token do dispositivo, como o resto da API (`01-modulo-auth.md`).

```
POST /api/integracao/health-connect/lote
{
  "enviadoEm": "2026-11-06T07:12:00-03:00",
  "registros": [
    { "id": "hc-8f2a…", "tipo": "EXERCICIO", "modalidade": "BIKING",
      "inicio": "…", "fim": "…", "distanciaM": 9800, "fcMedia": 132 },
    { "id": "hc-11c0…", "tipo": "PESO", "valor": 0.0, "medidoEm": "…" }
  ],
  "removidos": ["hc-77de…"]
}
→ { "gravados": 2, "atualizados": 0, "removidos": 1 }
```

O endpoint só grava dados brutos e despacha para o módulo certo. O vínculo com a sessão planejada é do módulo treino.

Limite de tamanho do lote (sugestão: 500 registros) — o endpoint é público na internet e não deve aceitar corpo arbitrariamente grande.

---

## GPS

Não é prioridade. Duas limitações para quando for:

- O Samsung Health **pode não repassar** o trajeto completo do treino para o Health Connect. Testar no seu aparelho antes de planejar em cima disso.
- Um app **não consegue ler rotas criadas por outros apps em segundo plano**. A rota só vem com o app aberto, e cada rota pode pedir um consentimento seu.

Se o Samsung Health não repassar, a alternativa é o próprio app gravar o GPS durante o pedal ou a corrida e escrever a rota no Health Connect. Dá mais trabalho, mas os dados ficam todos com você.

---

## Por que não o Strava

A API do Strava ainda funciona para os seus próprios dados, mas a política atual **proíbe usar os dados da API em aplicações de IA**, incluindo prompts, embeddings e RAG. Isso bate de frente com o módulo sistema, que usa IA para a revisão da semana. A decisão é buscar os dados na fonte (relógio → Health Connect) e deixar o Strava só como rede social, se você usar.

---

## Riscos

| Risco | Mitigação |
|---|---|
| Samsung Health para de escrever num tipo de dado depois de uma atualização | A tela de configurações mostra "último dado recebido" por tipo |
| Token de mudanças expira | Releitura da janela + idempotência |
| Permissão revogada sem você perceber | O app checa as permissões a cada sincronização e avisa |
| Mesmo treino registrado no relógio e à mão | O vínculo automático prefere o do Health Connect; o manual fica marcado como possível duplicado para você decidir |

---

## Pergunta em aberto

- Qual smartwatch você vai pegar? Isso define quais tipos de dado chegam de verdade.

---

## Fontes

- [Android Developers – Health Connect](https://developer.android.com/health-connect)
- [Android Developers – Exercise routes](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)
- [Sahha – Samsung Health Integration](https://sahha.ai/integrations/samsung-health/)
- [FitMesh – Samsung Health e Health Connect](https://www.fitmesh.fit/fi/blog/sync-samsung-health-google-fit)
- [Terra – Strava API Changes in 2026](https://tryterra.co/blog/strava-api-changes-2026)
