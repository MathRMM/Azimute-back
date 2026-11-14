# SPEC — Azimute Core: Fundação, Calculadora e Auth (Fases 1–2 do roadmap)

**PRD de referência:** `.planning/prd_fundacao_calculadora_auth.md` (v1.1, aprovado)
**Data:** 2026-11-07
**Status:** aprovado
**Autor:** Claude Code

> Nomenclatura: as "Fases 1–2" do título são as do roadmap geral (`00-arquitetura.md`). As **Fases 1 a 8** deste documento são as etapas de implementação deste SPEC.

---

## 1. Visão Geral Técnica

**Monólito modular** com Spring Boot 4.1 e Spring Modulith 2.1. Os pacotes são organizados por módulo, sob `br.dev.azimute`. Cada pacote de primeiro nível é um módulo de aplicação, e as fronteiras entre eles são verificadas por teste (`ApplicationModules.verify()`).

Módulos deste SPEC:

| Módulo | Natureza | Depende de |
|---|---|---|
| `comum` | Kernel: tipos de valor e a exceção base de negócio. Java puro, sem Spring, sem I/O | — |
| `plataforma` | Infraestrutura web transversal: tradução de erros e (de)serialização dos tipos do kernel | `comum` |
| `calculadora` | `core` (função pura) + `historico` (persistência e API) | `comum`, `plataforma` |
| `auth` | Segurança: usuário, dispositivos, filtro de token, limite de tentativas | `comum`, `plataforma` |
| `contas`, `fluxo`, `cartoes`, `reserva`, `treino`, `saude`, `sistema`, `agenda`, `integracao` | Pacotes vazios (só `package-info.java`) | — |

> **Decisão técnica T1 — módulo `plataforma`.** A regra 1 da doc 00 proíbe Spring em `comum`, mas a tradução de erros (`@RestControllerAdvice`) e o Jackson precisam de um lugar. `plataforma` é declarado como módulo aberto (`Type.OPEN`) para que qualquer módulo possa usá-lo, e ele não contém regra de negócio.

**Persistência:** Spring Data JDBC com SQL explícito onde houver consulta (comparação, limite de tentativas). As entidades de persistência são separadas dos records de domínio, e a conversão `Dinheiro` ↔ `BigDecimal` acontece no repositório. O schema é definido **somente** por Flyway, com um schema Postgres por módulo.

**Erros:** os módulos de domínio lançam subclasses de `ErroDeNegocio` (em `comum`), que carregam código, tipo e detalhes. O `plataforma` traduz o tipo em status HTTP e monta `{ erro, mensagem, detalhes }`. Os resultados do `core` que são fluxo normal usam retorno tipado; as exceções ficam para entradas inválidas (RN04, D5).

**Dinheiro e taxa na API (Decisão T2):** valores monetários e taxas trafegam em JSON como **string decimal** (`"17430.00"`, `"0.03513493"`), não como número. O motivo é que o cliente Kotlin, ao ler um número JSON, tende a convertê-lo em `Double`, que é justamente o erro que a RN01 proíbe. O formato também é o mesmo do arquivo de vetores de ouro. A taxa trafega como **fração**, e o app formata como percentual.

**Perfis:** `dev` (local: HTTP aceito, log legível) e `prod` (HTTPS exigido, log JSON, erro sem detalhe técnico). **Sem perfil ativo, valem as regras de `prod`**, ou seja, o padrão é o seguro (D1).

**Testes:** JUnit 5 + AssertJ; jqwik para os testes de propriedade (RNF10); Testcontainers com `postgres:18` e `@ServiceConnection` para tudo que toca o banco (RNF09).

## 2. Tecnologias e Versões

| Tecnologia | Versão | Uso |
|---|---|---|
| Java (toolchain do Gradle) | 25 | Linguagem (records, sealed, pattern matching) |
| Gradle (wrapper) | 9.x | Build, Kotlin DSL |
| Spring Boot | 4.1.x (último patch, confirmado no Initializr na Fase 1) | Web MVC, Data JDBC, Security, Actuator, Validation, Flyway |
| Spring Modulith | 2.1.x (pareado com Boot 4.1) | Verificação de módulos, Event Publication Registry (JDBC) |
| PostgreSQL | 18 (imagem `postgres:18`) | Banco; `uuidv7()` nativo |
| Flyway | gerenciado pelo Boot | Migrations |
| Testcontainers | gerenciado pelo Boot | Postgres real nos testes |
| jqwik | último estável | Testes de propriedade |
| Docker Compose | v2 | `app` + `postgres` locais |
| Eclipse Temurin | 25 (imagem base) | Container da aplicação |

## 3. Mapa de Arquivos

```
backend-azimute-core/
├── [REF] assistente-docs/…                          — documentação de origem
├── [REF] .planning/prd_fundacao_calculadora_auth.md
├── [MOD] .gitignore                                 — (se necessário) ignorar artefatos novos
├── [NEW] README.md                                  — como subir, testar e as variáveis de ambiente
├── [NEW] settings.gradle.kts                        — nome do projeto, plugin de resolução de toolchain
├── [NEW] build.gradle.kts                           — dependências, toolchain 25, jqwik, testes
├── [NEW] gradlew, gradlew.bat, gradle/wrapper/…     — wrapper do Gradle
├── [NEW] Dockerfile                                 — build multi-stage, TZ=America/Sao_Paulo
├── [NEW] compose.yaml                               — serviços app + postgres, rede interna, volume
├── [NEW] .env.example                               — chaves sem valor: APP_SENHA_INICIAL, DB_*
└── src/
    ├── main/
    │   ├── java/br/dev/azimute/
    │   │   ├── AzimuteApplication.java              — main; fixa o fuso padrão da JVM
    │   │   ├── comum/
    │   │   │   ├── package-info.java                — @ApplicationModule (OPEN)
    │   │   │   ├── Dinheiro.java                    — valor monetário (RF01)
    │   │   │   ├── Competencia.java                 — mês de referência (RF02)
    │   │   │   ├── Percentual.java                  — 0–100 inteiro (RF03)
    │   │   │   └── ErroDeNegocio.java               — exceção base: código, tipo, detalhes
    │   │   ├── plataforma/
    │   │   │   ├── package-info.java                — @ApplicationModule (OPEN)
    │   │   │   ├── web/ErroApi.java                 — record { erro, mensagem, detalhes }
    │   │   │   ├── web/TradutorDeErros.java         — @RestControllerAdvice (RF06, SEC06)
    │   │   │   └── web/JacksonKernelConfig.java     — Dinheiro/Competencia/taxa como string
    │   │   ├── calculadora/
    │   │   │   ├── package-info.java
    │   │   │   ├── core/                            — @NamedInterface "core"; Java puro
    │   │   │   │   ├── TaxaMensal.java              — RF10
    │   │   │   │   ├── JurosCompostos.java          — RF11–RF14
    │   │   │   │   ├── Comparador.java              — compararAVistaVsFinanciado (RF15)
    │   │   │   │   ├── Veredito.java
    │   │   │   │   ├── Sensibilidade.java           — curvaPorEntrada (RF16)
    │   │   │   │   ├── PontoSensibilidade.java
    │   │   │   │   ├── SemJurosException.java
    │   │   │   │   ├── NaoConvergiuException.java
    │   │   │   │   └── TaxaForaDaFaixaException.java
    │   │   │   └── historico/
    │   │   │       ├── SimulacaoId.java
    │   │   │       ├── SimulacaoSalva.java          — record de domínio (snapshot)
    │   │   │       ├── OrigemSimulacao.java, StatusSimulacao.java
    │   │   │       ├── ConferenciaDeSnapshot.java   — recalcula com o core (RF28, D6)
    │   │   │       ├── HistoricoService.java        — salvar idempotente, anotar, excluir, comparar
    │   │   │       ├── SimulacaoRepository.java     — JdbcAggregateTemplate/JdbcClient
    │   │   │       ├── SimulacaoRow.java            — linha de persistência
    │   │   │       ├── HistoricoController.java     — endpoints /api/calculadora/historico
    │   │   │       ├── dto/…                        — requisição/resposta (contratos da seção 6)
    │   │   │       └── erros/…                      — SIMULACAO_DIVERGENTE, SNAPSHOT_DIVERGENTE etc.
    │   │   ├── auth/
    │   │   │   ├── package-info.java
    │   │   │   ├── SecurityConfig.java              — filter chain, perfis, BCrypt(12)
    │   │   │   ├── HttpsObrigatorioFilter.java      — rejeita HTTP fora do perfil dev (SEC12)
    │   │   │   ├── TokenFilter.java                 — Bearer → dispositivo (RF32, RF36)
    │   │   │   ├── DispositivoAutenticado.java      — principal (id do dispositivo)
    │   │   │   ├── SeedUsuario.java                 — ApplicationRunner (RF30)
    │   │   │   ├── GeradorDeToken.java              — 256 bits + SHA-256
    │   │   │   ├── DispositivoService.java          — cadastrar, listar, revogar
    │   │   │   ├── LimiteDeTentativas.java          — RN16, RN17
    │   │   │   ├── SenhaService.java                — troca de senha (RF35, RN15)
    │   │   │   ├── UsuarioRepository.java, DispositivoRepository.java, TentativaRepository.java
    │   │   │   ├── AuthController.java              — /api/auth/…
    │   │   │   └── erros/…                          — CREDENCIAIS_INVALIDAS, LOGIN_BLOQUEADO etc.
    │   │   └── {contas,fluxo,cartoes,reserva,treino,saude,sistema,agenda,integracao}/package-info.java
    │   └── resources/
    │       ├── application.yml                      — comum a todos os perfis (padrão seguro)
    │       ├── application-dev.yml                  — HTTP aceito, log de console legível
    │       ├── application-prod.yml                 — log JSON, forward-headers
    │       └── db/migration/
    │           ├── V001__fundacao.sql               — schemas, impedir_alteracao(), tabela de eventos
    │           ├── V002__calculadora.sql            — simulacoes + triggers
    │           └── V003__auth.sql                   — usuarios, dispositivos, tentativas_login
    └── test/
        ├── java/br/dev/azimute/
        │   ├── TestcontainersConfig.java            — postgres:18 com @ServiceConnection
        │   ├── ModularidadeTest.java                — ApplicationModules.verify()
        │   ├── FundacaoIT.java                      — contexto, Flyway, uuidv7, fuso, health, eventos
        │   ├── comum/…Test.java
        │   ├── plataforma/TradutorDeErrosTest.java
        │   ├── calculadora/core/…Test.java          — vetores + propriedades
        │   ├── calculadora/historico/…IT.java
        │   └── auth/…IT.java
        └── resources/
            └── vetores-juros.json                   — vetores de ouro (RF17)
```

> O arquivo de vetores fica em `src/test/resources`. Quando o app Kotlin existir, ele consome **o mesmo arquivo** (por cópia versionada ou submódulo [PENDENTE: definir na criação do repositório do app; fora do escopo deste PRD]).

---

## 4. Fases de Implementação

### Fase 1 — Esqueleto do projeto

**Objetivo:** projeto compilando com Java 25, subindo com `docker compose up`, banco migrado e fronteiras de módulo verificadas.

**Arquivos desta fase:** `settings.gradle.kts`, `build.gradle.kts`, wrapper, `Dockerfile`, `compose.yaml`, `.env.example`, `README.md`, `AzimuteApplication.java`, `package-info.java` de todos os módulos (inclusive os vazios), `application*.yml`, `V001__fundacao.sql`, `TestcontainersConfig.java`, `ModularidadeTest.java`, `FundacaoIT.java`.

**O que fazer:**
- Confirmar no Spring Initializr os patches exatos de Boot 4.1.x e Modulith 2.1.x e fixá-los (RNF01). Toolchain Java 25, com resolução automática habilitada como plano B, caso o JDK local não seja o 25.
- Dependências: Web MVC, Data JDBC, Flyway + driver Postgres, Security, Actuator, Validation, Modulith (core, JDBC events, test), Testcontainers, jqwik (RNF03, RNF09, RNF10).
- `V001__fundacao.sql`: cria os schemas `calculadora` e `auth`, a função `impedir_alteracao()` (RF07) e a tabela de publicação de eventos do Modulith **copiada do script oficial da versão fixada**, pois o schema não é criado automaticamente (RNF03, RF05).
- Fuso `America/Sao_Paulo` em quatro lugares: JVM (no `main`), Jackson, container da app (`TZ`) e container do Postgres (`TZ`/`PGTZ`) (RNF05).
- Virtual threads ligadas (RNF06); log estruturado JSON no `prod` com o suporte nativo do Boot (RNF12).
- Actuator: expor só `health`, sem detalhes (SEC09; a autenticação do resto chega na Fase 7).
- Compose: Postgres sem porta publicada no host por padrão [observação: para acessar o banco pela IDE em dev, um `compose.override.yaml` local, ignorado pelo git, pode publicar a porta] (SEC10). Credenciais lidas do `.env` (SEC05).
- Pacotes vazios dos módulos futuros (RF08); `comum` e `plataforma` como módulos `OPEN`; `calculadora.core` como interface nomeada.

**Testes (TDD):**
- [x] `ModularidadeTest` → dada a estrutura de pacotes, quando verificar os módulos, então não há violação; e um import proibido de exemplo (validado manualmente uma vez) faz o teste falhar
- [x] `FundacaoIT` → dado o contexto com Postgres 18, quando subir, então o Flyway aplica a V001 sem erro
- [x] `FundacaoIT` → quando executar `SELECT uuidv7()`, então retorna um UUID versão 7
- [x] `FundacaoIT` → quando consultar `current_setting('TimeZone')` pela conexão da app e `ZoneId.systemDefault()`, então ambos são `America/Sao_Paulo`
- [x] `FundacaoIT` → quando publicar um evento de teste dentro de uma transação, então ele aparece registrado na tabela de publicações (RF05)
- [x] `FundacaoIT` → quando `GET /actuator/health`, então 200 com `{"status":"UP"}` e sem `components`/`details`
- [x] `FundacaoIT` → dado um `UPDATE` em uma tabela de teste com trigger `impedir_alteracao`, então o banco lança exceção com "Registro imutável"

**Checklist de validação:**
- [x] `./gradlew build` passa com Java 25 (RNF01)
- [x] `docker compose up` sobe app + postgres, e o health responde (RNF11)
- [x] Nenhuma propriedade `ddl-auto` ou inicialização automática de schema configurada (RNF03)
- [x] `.env` está no `.gitignore`; só o `.env.example` é versionado (SEC05)
- [x] Todos os testes desta fase passando

**Critério de avanço:** checklist completo → Fase 2.

---

### Fase 2 — Kernel `comum` e formato de erro

**Objetivo:** tipos de valor prontos para todo o sistema, e qualquer erro da API saindo no formato único.

**Arquivos desta fase:** `comum/Dinheiro.java`, `Competencia.java`, `Percentual.java`, `ErroDeNegocio.java`; `plataforma/web/ErroApi.java`, `TradutorDeErros.java`, `JacksonKernelConfig.java`; testes correspondentes.

**O que fazer:**
- `Dinheiro` conforme a doc 00 (escala 2, `HALF_EVEN`), com as operações do contrato da seção 6 (RF01, RN01).
- `Competencia` com aritmética, intervalo, formatação e leitura em `yyyy-MM` (RF02, RN03). A serialização JSON fica no `plataforma`.
- `Percentual` inteiro de 0 a 100 (RF03, RN02).
- `ErroDeNegocio`: exceção abstrata com `codigo` (`SCREAMING_SNAKE`), `mensagem` em português, `detalhes` (mapa) e `tipo` (VALIDACAO, NAO_ENCONTRADO, CONFLITO, NAO_AUTENTICADO, BLOQUEADO).
- `TradutorDeErros`: `ErroDeNegocio` → status pelo tipo (422, 404, 409, 401, 429); falha de Bean Validation → 422 `DADOS_INVALIDOS`, com os campos em `detalhes`; JSON malformado → 400 `REQUISICAO_MALFORMADA`; qualquer outra exceção → 500 `ERRO_INTERNO`, com mensagem genérica e o stack trace **só no log** (RF06, SEC06).
- Jackson: `Dinheiro` e `Competencia` como string (Decisão T2).

**Testes (TDD):**
- [x] `Dinheiro` → dado `"0.125"`, então vale `0.12`; dado `"0.135"`, então `0.14` (HALF_EVEN)
- [x] `Dinheiro` → `0.10 + 0.20 == 0.30` exatamente; `menos` produz negativo e `negativo()` é verdadeiro
- [x] `Dinheiro` → valor nulo é rejeitado
- [x] Propriedade (jqwik) → para quaisquer `a`, `b`: `a.mais(b).menos(b) == a`
- [x] `Competencia` → `2026-12` `proxima()` = `2027-01`; `mais(14)` cruza o ano; `ate` inclui as duas pontas
- [x] `Competencia` → `"2026-09"` ida e volta; `"2026-9"`, `"09/2026"` e `"2026-09-01"` são rejeitados
- [x] `Percentual` → `-1` e `101` rejeitados; `45` → fração `0.45`
- [x] `TradutorDeErros` → `ErroDeNegocio` do tipo CONFLITO → 409 com `erro`, `mensagem` e `detalhes`
- [x] `TradutorDeErros` → exceção inesperada no perfil `prod` → 500 sem stack trace nem nome de classe no corpo
- [x] Jackson → `Dinheiro("17430.00")` serializa como `"17430.00"`; `Competencia` como `"2026-09"`

**Checklist de validação:**
- [x] `comum` sem nenhum import de Spring ou Jackson (regra 1 da doc 00)
- [x] RF01–RF03, RF06, RN01–RN03, SEC06 atendidos
- [x] Testes das fases 1 e 2 passando

**Critério de avanço:** checklist completo → Fase 3.

---

### Fase 3 — Calculadora `core`, parte 1

**Objetivo:** as quatro operações de juros corretas, provadas pelos vetores de ouro e pela propriedade de ida e volta.

**Arquivos desta fase:** `calculadora/core/TaxaMensal.java`, `JurosCompostos.java`, as três exceções; `src/test/resources/vetores-juros.json`; testes.

**O que fazer:**
- `TaxaMensal` (fração, não negativa; `dePercentual`, `percentual`, `anualizada`) (RF10).
- `montante`, `parcela` (Price) e `valorPresente`, com `MathContext.DECIMAL64` nos cálculos intermediários e arredondamento só no `Dinheiro` final (RF11–RF13, RN06).
- `taxaImplicita` por bisseção em `[1e-6; 0,30]`, tolerância 1e-10, até 200 iterações (RF14):
  - `PMT × n ≤ PV` → `SemJurosException` (RN04);
  - `PV ≤ 0`, `PMT ≤ 0` ou `n ≤ 0` → erro de validação (RN04);
  - sem troca de sinal no intervalo, com taxa real > 30% → `TaxaForaDaFaixaException` (D5);
  - 200 iterações sem convergir → `NaoConvergiuException`.
- `parcela` com taxa zero: divide o valor por `n` (caso necessário para a D7, em que não há juros).
- `vetores-juros.json` com os seis casos da RN07 (RF17). O `core` não importa nada fora de `comum` e do JDK.

**Testes (TDD):**
- [x] Vetores → para cada caso do JSON, `taxaImplicita(pv, pmt, n)` está dentro de 1e-7 do esperado; anualizada arredondada a 1 casa percentual bate (51,3%; 62,6%; …)
- [x] Vetores → `parcela(pv, taxaEsperada, n)` reproduz o PMT do vetor com diferença ≤ R$ 0,01
- [x] Propriedade (jqwik, ≥ 1000 casos) → `pv` ∈ [100; 1.000.000], `i` ∈ [0,0001; 0,25], `n` ∈ [1; 120]: `taxaImplicita(pv, parcela(pv, i, n), n) ≈ i` (1e-8) (RN05) [observação: a parcela é arredondada a centavos, e para `pv` pequeno com `n` grande isso pode exceder 1e-8; se ocorrer, a propriedade usa a parcela sem arredondar, via um método interno de precisão total]
- [x] Propriedade → `valorPresente(parcela(pv, i, n), i, n) ≈ pv` (≤ R$ 0,01 × n)
- [x] Borda → `n = 1`: taxa = PMT/PV − 1 exatamente
- [x] Borda → `PMT × n == PV` → `SemJurosException`
- [x] Borda → `PV = 0`, `PMT = 0`, `n = 0`, `n = −1` → rejeitados
- [x] Borda → PV 1.000, PMT 400, n 3 (≈ 9,7% a.m.) converge; PV 1.000, PMT 600, n 3 (> 30% a.m.) → `TaxaForaDaFaixaException`
- [x] Borda → `taxa_muito_baixa` converge (sem divergir perto de 1e-6)
- [x] `TaxaMensal` → `dePercentual("3.51")` = 0,0351; taxa negativa rejeitada
- [x] Desempenho → `taxaImplicita` no vetor moto 36x < 10 ms após aquecimento (RNF07)

**Checklist de validação:**
- [x] RF10–RF14, RF17, RN04–RN07 atendidos
- [x] `ModularidadeTest` continua verde (o `core` só depende de `comum`)
- [x] Testes das fases 1–3 passando

**Critério de avanço:** checklist completo → Fase 4.

---

### Fase 4 — Calculadora `core`, parte 2

**Objetivo:** comparação à vista × financiado e curva de sensibilidade, completando o `core` que o app espelha.

**Arquivos desta fase:** `calculadora/core/Comparador.java`, `Veredito.java`, `Sensibilidade.java`, `PontoSensibilidade.java`; testes; novos casos em `vetores-juros.json` (seção `comparacao`).

**O que fazer:**
- `compararAVistaVsFinanciado` exatamente pela fórmula D7 do PRD, devolvendo o delta em reais (RF15). Quando não há juros (`parcela × prazo ≤ precoAVista − entrada`), `taxaFinanciamento` = zero e o cálculo segue.
- `curvaPorEntrada` (RF16) — **decisão T7** (aprovada pelo usuário em 2026-11-07): `passos` pontos igualmente espaçados com entrada de R$ 0 até o **valor do bem inteiro** (o último ponto é o cenário à vista: parcela e juros zero, como pede o `prompt-simulador-entrada.md`), sempre incluindo o ponto exato da âncora, sem duplicá-lo; `distanciaDaAncora = |entrada − entradaAncora| ÷ valorBem`, escala 4, `HALF_EVEN` (0 na âncora, crescente com a extrapolação, sempre em [0; 1], comparável entre bens de valores diferentes).
- Taxa constante ao longo da curva; o `core` não modela variação da taxa (premissa declarada da doc 02).

**Testes (TDD):**
- [x] Veredito → com o vetor moto 24x (preço à vista = 13.990 + entrada 0, parcela 930,42, 24×) e rendimento de 0,8% a.m., o delta é positivo (à vista vence), e o valor em reais bate com o vetor de comparação
- [x] Veredito → com financiamento a 0,6% a.m. e rendimento a 0,8% a.m., o delta é negativo (financiar vence)
- [x] Veredito → identidade: `delta == custoTotalFinanciado − rendimentoDoDinheiroGuardado − custoTotalAVista`, sempre (propriedade)
- [x] Veredito → taxa de financiamento zero não lança exceção
- [x] Curva → o ponto da âncora tem `distanciaDaAncora = 0` e parcela igual a `parcela(valorBem − entradaAncora, taxa, prazo)`
- [x] Curva → a parcela é decrescente conforme a entrada cresce; a distância é crescente a partir da âncora
- [x] Curva → o primeiro ponto tem entrada R$ 0; o último tem entrada = valor do bem, com parcela, total pago e juros zero
- [x] Curva → a âncora aparece uma única vez, mesmo quando coincide com um ponto da grade
- [x] Curva → `distanciaDaAncora` no exemplo do prompt (bem R$ 19.990, âncora R$ 6.000, ponto R$ 9.000) vale 0,1501
- [x] Curva → `passos < 2`, entrada âncora ≥ valor do bem ou negativa → rejeitados

**Checklist de validação:**
- [x] RF15, RF16 atendidos; semântica da curva definida (T7)
- [x] Testes das fases 1–4 passando

**Critério de avanço:** checklist completo → Fase 5.

---

### Fase 5 — Histórico: gravação

**Objetivo:** o app consegue salvar uma simulação offline e reenviá-la sem duplicar, e o banco protege o snapshot.

**Arquivos desta fase:** `V002__calculadora.sql`; `historico/SimulacaoId`, `SimulacaoSalva`, `OrigemSimulacao`, `StatusSimulacao`, `ConferenciaDeSnapshot`, `SimulacaoRow`, `SimulacaoRepository`, `HistoricoService` (salvar e listar), `HistoricoController` (`PUT` e `GET`), DTOs e erros; testes.

**O que fazer:**
- `V002`: tabela `calculadora.simulacoes` conforme a doc 02 (ID **sem** `DEFAULT`, com comentário no DDL), índices, função e trigger `simulacao_valores_imutaveis` (RF25, RF26, RN09–RN11).
- Inserção com ID do cliente pelo `JdbcAggregateTemplate.insert` (não `save`, que tentaria um `UPDATE` com ID preenchido).
- `PUT /api/calculadora/historico/{id}` (RF20, RF27, RF28):
  1. Valida o corpo (Bean Validation) e as regras RN10/RN11; o ID do path deve ser um UUID.
  2. `ConferenciaDeSnapshot` recalcula com o `core` conforme a D6; divergência → 422 `SNAPSHOT_DIVERGENTE`, com os campos divergentes em `detalhes`. Estimativa com âncora inexistente ou âncora que não é `PROPOSTA` → 422 `ANCORA_INVALIDA`.
  3. ID já existe: valores iguais → 200 com o existente; valores diferentes → 409 `SIMULACAO_DIVERGENTE` (D2).
  4. ID novo → insere, `sincronizada_em = now()`, 200 com o registro.
  5. Corrida entre dois `PUT` simultâneos com o mesmo ID: a violação de PK é capturada e cai no passo 3.
- `GET /api/calculadora/historico[?bem=]` → lista em ordem de `data` decrescente; o filtro por `bem` ignora maiúsculas e espaços nas pontas (RF21).
- A calculadora não publica eventos (RN13), e não existe endpoint de cálculo (RN14).

**Testes (TDD):**
- [x] Gravação → dado um corpo válido com o vetor moto 36x, quando `PUT`, então 200 e o registro aparece no `GET`
- [x] Idempotência → mesmo `PUT` duas vezes → um registro só, ambas as respostas 200 e iguais
- [x] Idempotência → mesmo ID com `parcela` diferente → 409 `SIMULACAO_DIVERGENTE`
- [x] Idempotência → mesmo ID com só `observacao` diferente → 200 com o registro original
- [x] Concorrência → dois `PUT` simultâneos com o mesmo ID → um registro, nenhuma resposta 500
- [x] Snapshot → `taxaMensal` enviada fora da tolerância → 422 `SNAPSHOT_DIVERGENTE`
- [x] Snapshot → `totalJuros` errado em R$ 0,01 → 422
- [x] Estimativa → sem `ancoraId` → 422 (aplicação) e, num `INSERT` direto, violação da constraint (banco)
- [x] Estimativa → com âncora válida e parcela recalculada pela taxa da âncora → 200
- [x] Banco → `UPDATE` de `parcela` direto no SQL → exceção do trigger; `UPDATE` de `observacao` → permitido
- [x] Validação → `entrada ≥ valorBem`, `prazo = 0`, `parcela` negativa → 422 `DADOS_INVALIDOS`
- [x] Filtro → `?bem=honda cb 300f ` encontra `"Honda CB 300F"`
- [x] `calculadaEm` é preservado como veio do cliente; `sincronizadaEm` é preenchido pelo servidor

**Checklist de validação:**
- [x] RF20, RF21, RF25–RF28, RN09–RN14 atendidos
- [x] Testes das fases 1–5 passando

**Critério de avanço:** checklist completo → Fase 6.

---

### Fase 6 — Histórico: anotação, exclusão e comparação

**Objetivo:** o usuário anota, descarta, exclui e compara propostas, e leva para a negociação a economia em reais.

**Arquivos desta fase:** `HistoricoService` e `HistoricoController` (complemento), DTOs de patch e de comparação, consulta SQL de comparação; testes.

**O que fazer:**
- `PATCH /{id}`: aceita só `observacao` e `status`; qualquer outro campo → 422 `CAMPO_NAO_EDITAVEL`; ID inexistente → 404 `SIMULACAO_NAO_ENCONTRADA`; status fora do domínio → 422 (RF23).
- `DELETE /{id}`: âncora de estimativas → 409 `SIMULACAO_E_ANCORA`, com os IDs em `detalhes` (D3); inexistente → 204, para ser idempotente (Decisão T3); sucesso → 204 (RF24).
- `GET /comparar?bem=…[&incluirEstimativas=false]` (RF22, D4): só `PROPOSTA` por padrão; a melhor é a de menor `taxa_mensal`, com desempate pela `data` mais recente; `economiaEntreMelhorEPior = total_juros(pior) − total_juros(melhor)`; cada item traz `prazo` e `valorFinanciado`. `bem` é obrigatório (ausente → 422).

**Testes (TDD):**
- [x] Patch → `observacao` e `status=ACEITA` → 200, refletidos no `GET`
- [x] Patch → corpo com `parcela` → 422 `CAMPO_NAO_EDITAVEL`, e nada muda
- [x] Patch → ID inexistente → 404
- [x] Delete → proposta sem estimativas → 204 e some da lista; repetir → 204
- [x] Delete → proposta âncora → 409 `SIMULACAO_E_ANCORA` com os IDs das estimativas
- [x] Comparar → duas propostas coerentes (que passam pela conferência do snapshot) do mesmo bem, com taxas diferentes → a de menor taxa é a melhor, e a economia é exatamente a diferença dos `totalJuros` (os números do exemplo da doc 02 são ilustrativos e não são coerentes entre si; o teste usa valores gerados pelo `core`)
- [x] Comparar → uma estimativa com taxa menor não vira a "melhor" por padrão; com `incluirEstimativas=true`, entra
- [x] Comparar → uma proposta só → ela é a melhor e a economia é `"0.00"`; nenhuma → lista vazia e `"0.00"`
- [x] Comparar → empate de taxa → vence a data mais recente

**Checklist de validação:**
- [x] RF22–RF24, D3, D4 atendidos
- [x] Testes das fases 1–6 passando

**Critério de avanço:** checklist completo → Fase 7.

---

### Fase 7 — Auth, parte 1: usuário, dispositivos e token

**Objetivo:** todos os endpoints de negócio passam a exigir o token de um dispositivo cadastrado.

**Arquivos desta fase:** `V003__auth.sql`; `auth/SecurityConfig`, `HttpsObrigatorioFilter`, `TokenFilter`, `DispositivoAutenticado`, `SeedUsuario`, `GeradorDeToken`, `DispositivoService`, repositórios, `AuthController` (cadastrar, listar, revogar), erros; `application-*.yml` [MOD]; `FundacaoIT` e os ITs do histórico [MOD] (passam a autenticar).

**O que fazer:**
- `V003`: tabelas `auth.usuarios`, `auth.dispositivos` e `auth.tentativas_login` conforme a doc 01 (a tabela de tentativas já nasce aqui, mas só é usada na Fase 8).
- `SeedUsuario`: sem usuário e sem `APP_SENHA_INICIAL` → falha na subida; senha inicial com menos de 12 caracteres → falha na subida; cria o login `admin` com BCrypt(12) e loga um aviso sem a senha (RF30, RN15, RN19, SEC01, SEC04, SEC07).
- `POST /api/auth/dispositivos` (público): valida login e senha; gera 32 bytes de `SecureRandom`, codificados em base64url; grava só o SHA-256 em hex; devolve `{ id, token }` uma única vez; atualiza `ultimo_acesso` do usuário. Credencial errada → 401 `CREDENCIAIS_INVALIDAS` (RF31, SEC02).
- `TokenFilter`: lê o `Bearer`, calcula o hash, busca o dispositivo com `revogado_em IS NULL` e coloca no contexto um `DispositivoAutenticado`. O `ultimo_uso` é atualizado com uma condição no próprio `UPDATE` (nulo ou mais antigo que 1 minuto) (RF32, RF36, RNF08).
- `GET /api/auth/dispositivos` e `DELETE /api/auth/dispositivos/{id}` (RF33, RF34). A revogação é idempotente e o efeito é imediato.
- `SecurityConfig`: stateless, sem Basic, sem form login, CSRF desligado; públicos só `/actuator/health` e `POST /api/auth/dispositivos`; todo o resto autenticado, inclusive os outros endpoints do Actuator; cabeçalhos `X-Content-Type-Options` e HSTS; 401 no formato `ErroApi` (SEC03, SEC09, SEC11). A rota do callback do Google **não** é aberta.
- `HttpsObrigatorioFilter` (Decisão T4): fora do perfil `dev`, requisição não segura → 403 `HTTPS_OBRIGATORIO`. Rejeitar em vez de redirecionar, porque redirecionar uma API que recebeu senha em HTTP já vazou a senha. `server.forward-headers-strategy=framework` no `prod` (SEC12, D1).

**Testes (TDD):**
- [x] Sem token → `GET /api/calculadora/historico` → 401 `NAO_AUTENTICADO` no formato `ErroApi`
- [x] Token inválido → 401; token revogado → 401; token válido → 200
- [x] Cadastro → credenciais corretas → 200 com token; o banco contém só o hash (64 caracteres hex), e o token não aparece em nenhuma coluna
- [x] Cadastro → senha errada e login inexistente → mesmo status e mesmo corpo (a igualdade de tempo é testada na Fase 8)
- [x] Revogação → `DELETE` do próprio ou de outro dispositivo → requisições seguintes com aquele token → 401
- [x] Listagem → retorna nome, `criadoEm` e `ultimoUso`, nunca o hash
- [x] `ultimo_uso` → duas requisições no mesmo minuto → uma escrita só
- [x] Seed → contexto sem usuário e sem a variável → a aplicação não sobe; com senha de 11 caracteres → não sobe; com usuário existente e sem a variável → sobe
- [x] HTTPS (perfil `prod`) → HTTP puro → 403 `HTTPS_OBRIGATORIO`; com `X-Forwarded-Proto: https` → aceito
- [x] HTTPS (perfil `dev`) → HTTP puro aceito
- [x] Sessão → nenhuma resposta traz `Set-Cookie: JSESSIONID`
- [x] Actuator → `/actuator/health` público; `/actuator/info` (ou outro exposto) sem token → 401

**Checklist de validação:**
- [x] RF30–RF34, RF36, RN19, SEC01–SEC05, SEC07, SEC09–SEC12 atendidos
- [x] Os ITs da calculadora foram atualizados para autenticar e continuam verdes
- [x] Testes das fases 1–7 passando

**Critério de avanço:** checklist completo → Fase 8.

---

### Fase 8 — Auth, parte 2: limite de tentativas e troca de senha

**Objetivo:** força bruta fica inviável sem trancar o dono para fora, e a senha pode ser trocada com segurança.

**Arquivos desta fase:** `auth/LimiteDeTentativas`, `TentativaRepository`, `SenhaService`, `AuthController` [MOD], `DispositivoService` [MOD]; testes.

**O que fazer:**
- Registrar cada tentativa de cadastro de dispositivo com o IP, o horário e o resultado (RF37, SEC08). O IP vem de `request.getRemoteAddr()`, que no `prod` já reflete o `X-Forwarded-For` tratado pelo framework.
- Antes de validar a senha (RN16, RN17):
  - ≥ 5 falhas do IP nos últimos 15 minutos → 429 `LOGIN_BLOQUEADO`, com `detalhes.liberaEm`;
  - ≥ 20 falhas globais na última hora → 429 `LOGIN_BLOQUEADO_GLOBAL`;
  - Tentativa bloqueada não é registrada, para não estender o bloqueio indefinidamente (Decisão T5).
- O bloqueio afeta só o `POST /api/auth/dispositivos`; os tokens existentes continuam funcionando (RN17).
- Login inexistente: comparar contra um hash BCrypt fictício, para igualar o tempo (RN18).
- Log de falha com o IP e sem a senha (SEC07, SEC08).
- `POST /api/auth/senha` (RF35, RN15): exige `senhaAtual` correta (errada → 401 `CREDENCIAIS_INVALIDAS`); `senhaNova` com 12 caracteres ou mais (senão 422 `SENHA_CURTA`); grava o novo hash e revoga todos os dispositivos, **exceto** o da requisição, numa única transação.

**Testes (TDD):**
- [x] Limite por IP → 5 falhas do IP A → a 6ª tentativa, mesmo com a senha certa, → 429; o IP B continua podendo tentar
- [x] Limite por IP → falhas com mais de 15 minutos não contam (horário controlado por um `Clock` injetado)
- [x] Limite global → 20 falhas de IPs variados → cadastro bloqueado para todos → 429; um token já emitido continua → 200
- [x] Tentativa bloqueada não gera linha em `tentativas_login`
- [x] Toda tentativa não bloqueada gera uma linha com IP e resultado
- [x] Tempo → a mediana do tempo de resposta para login inexistente e para senha errada difere menos de 30% (teste com amostragem, tolerante a ruído)
- [x] Troca de senha → sem `senhaAtual` ou com a atual errada → falha, e nada muda
- [x] Troca de senha → nova com 11 caracteres → 422 `SENHA_CURTA`
- [x] Troca de senha → sucesso → o dispositivo atual continua → 200; os outros → 401; a senha antiga deixa de cadastrar dispositivos
- [x] Log → o conteúdo capturado do log não contém a senha nem o token

**Checklist de validação:**
- [x] RF35, RF37, RN15–RN18, SEC07, SEC08 atendidos
- [x] Todos os critérios de aceitação do PRD (seção 10) marcados
- [x] Testes das fases 1–8 passando

**Critério de avanço:** fim deste SPEC. Próximo passo: PRD de implantação (VPS, Caddy, backup) ou PRD do módulo `contas` (fase 3 do roadmap).

---

## 5. Ordem de Execução e Dependências

```
Fase 1 → Fase 2 → Fase 3 → Fase 4 → Fase 5 → Fase 6 → Fase 7 → Fase 8
```

- A Fase 2 vem antes do `core` porque `Dinheiro` é a base de todas as assinaturas.
- A Fase 5 depende das fases 3 **e** 4 apenas indiretamente: a conferência do snapshot usa só a Fase 3. Ainda assim, mantém a ordem para que o `core` fique fechado antes da persistência.
- A Fase 7 altera os testes de integração das fases 5 e 6, que passam a enviar o token. Até a Fase 7, os endpoints da calculadora ficam abertos, o que é aceitável porque o backend só roda localmente (PRD, Fora do Escopo).
- Nenhuma fase roda em paralelo: o contexto é único e as migrations são sequenciais.
- **Antes da Fase 1:** Docker acessível no WSL e JDK 25 disponível (ou a toolchain automática).

## 6. Contratos e Interfaces

### 6.1 Kernel (`comum`)

```java
public record Dinheiro(BigDecimal valor) implements Comparable<Dinheiro> {
    public static final Dinheiro ZERO;
    public static Dinheiro de(String v);
    public Dinheiro mais(Dinheiro o);
    public Dinheiro menos(Dinheiro o);
    public Dinheiro vezes(int n);
    public boolean negativo();
    public boolean positivo();
}

public record Competencia(YearMonth mes) implements Comparable<Competencia> {
    public static Competencia de(String yyyyMM);   // rejeita qualquer outro formato
    public Competencia proxima();
    public Competencia mais(int meses);
    public Stream<Competencia> ate(Competencia fim); // inclusivo
    public String toString();                       // "yyyy-MM"
}

public record Percentual(int pontos) {
    public BigDecimal fracao();
}

public abstract class ErroDeNegocio extends RuntimeException {
    public enum Tipo { VALIDACAO, NAO_ENCONTRADO, CONFLITO, NAO_AUTENTICADO, BLOQUEADO }
    protected ErroDeNegocio(String codigo, String mensagem, Tipo tipo, Map<String, Object> detalhes);
    public String codigo();
    public Tipo tipo();
    public Map<String, Object> detalhes();
}
```

### 6.2 Erro da API (`plataforma`)

```java
public record ErroApi(String erro, String mensagem, Map<String, Object> detalhes) {}
```

| Tipo | Status |
|---|---|
| VALIDACAO | 422 |
| NAO_ENCONTRADO | 404 |
| CONFLITO | 409 |
| NAO_AUTENTICADO | 401 |
| BLOQUEADO | 429 |
| (inesperado) | 500 `ERRO_INTERNO` |

### 6.3 Calculadora `core`

```java
public record TaxaMensal(BigDecimal fracao) {
    public static final TaxaMensal ZERO;
    public static TaxaMensal dePercentual(String pct);
    public BigDecimal percentual();
    public TaxaMensal anualizada();
}

public final class JurosCompostos {
    public static Dinheiro montante(Dinheiro pv, TaxaMensal i, int n);
    public static Dinheiro parcela(Dinheiro pv, TaxaMensal i, int n);
    public static Dinheiro valorPresente(Dinheiro pmt, TaxaMensal i, int n);
    public static TaxaMensal taxaImplicita(Dinheiro pv, Dinheiro pmt, int n);
}

public record Veredito(
    Dinheiro custoTotalFinanciado, Dinheiro custoTotalAVista,
    Dinheiro rendimentoDoDinheiroGuardado, Dinheiro delta,
    TaxaMensal taxaFinanciamento, TaxaMensal taxaRendimento) {}

public record PontoSensibilidade(
    Dinheiro entrada, Dinheiro financiado, Dinheiro parcela,
    Dinheiro totalPago, Dinheiro totalJuros, BigDecimal distanciaDaAncora) {}
```

Exceções (`ErroDeNegocio`, tipo VALIDACAO): `SemJurosException` → `SEM_JUROS`; `TaxaForaDaFaixaException` → `TAXA_FORA_DA_FAIXA`; `NaoConvergiuException` → `NAO_CONVERGIU`.

### 6.4 Vetores de ouro (`vetores-juros.json`)

```json
{
  "taxaImplicita": [
    { "caso": "moto_36x_proposta_real", "pv": "17430.00", "pmt": "860.69", "n": 36,
      "taxaMensal": "0.0351349", "toleranciaTaxa": "0.0000001",
      "taxaAnualPercentual1Casa": "51.3", "totalPago": "30984.84", "totalJuros": "13554.84" }
  ],
  "comparacao": [ ]
}
```

(Os seis casos da RN07 entram em `taxaImplicita`; a Fase 4 preenche `comparacao`.)

### 6.5 API — Calculadora

`PUT /api/calculadora/historico/{id}` — requisição:

```json
{
  "bem": "Honda CB 300F",
  "data": "2026-09-12",
  "local": "Concessionária X, Campinas",
  "instituicao": "Banco Y",
  "valorBem": "17430.00",
  "entrada": "0.00",
  "parcela": "860.69",
  "prazo": 36,
  "valorFinanciado": "17430.00",
  "taxaMensal": "0.03513493",
  "taxaAnual": "0.51343400",
  "totalPago": "30984.84",
  "totalJuros": "13554.84",
  "origem": "PROPOSTA",
  "ancoraId": null,
  "observacao": null,
  "calculadaEm": "2026-09-12T15:42:10-03:00"
}
```

Resposta 200: o mesmo objeto, mais `id`, `status` e `sincronizadaEm`.

`PATCH /api/calculadora/historico/{id}` — `{ "observacao": "…", "status": "ACEITA" }` (ambos opcionais, nenhum outro campo aceito).

`GET /api/calculadora/historico/comparar?bem=…&incluirEstimativas=false`:

```json
{
  "bem": "Honda CB 300F",
  "propostas": [
    { "id": "…", "instituicao": "Financeira da montadora", "data": "2026-09-14",
      "taxaMensal": "0.03200000", "prazo": 36, "valorFinanciado": "…",
      "totalJuros": "9840.00", "melhor": true }
  ],
  "economiaEntreMelhorEPior": "3714.84"
}
```

### 6.6 API — Auth

```
POST   /api/auth/dispositivos      { login, senha, nomeDispositivo } → 200 { id, token }
GET    /api/auth/dispositivos      → 200 [ { id, nome, criadoEm, ultimoUso, atual } ]
DELETE /api/auth/dispositivos/{id} → 204
POST   /api/auth/senha             { senhaAtual, senhaNova } → 204
```

Códigos de erro: `CREDENCIAIS_INVALIDAS` (401), `NAO_AUTENTICADO` (401), `LOGIN_BLOQUEADO` / `LOGIN_BLOQUEADO_GLOBAL` (429), `SENHA_CURTA` (422), `HTTPS_OBRIGATORIO` (403).

> 403 não é um tipo de `ErroDeNegocio`: o `HttpsObrigatorioFilter` responde direto, antes do Spring MVC, mas no mesmo formato `ErroApi`.

## 7. Riscos Técnicos

| Risco | Fase afetada | Mitigação |
|---|---|---|
| Docker indisponível no WSL (verificado: hoje não está) | 1 em diante | Ativar a integração com o WSL no Docker Desktop antes da Fase 1 (bloqueio) |
| JDK local é 17 | 1 | SDKMAN (`sdk install java 25-tem`) ou toolchain automática do Gradle |
| O schema da tabela de eventos do Modulith muda entre versões | 1 | Copiar o script da versão fixada; um teste de publicação de evento quebra se divergir |
| `save()` do Spring Data JDBC com ID preenchido faz `UPDATE` em vez de `INSERT` | 5 | Usar `JdbcAggregateTemplate.insert`; um teste de gravação nova cobre |
| Arredondamento a centavos da parcela faz a propriedade de ida e volta falhar em extremos | 3 | Domínio da propriedade limitado (seção da Fase 3), mais o método de precisão total |
| Tolerância 1e-6 da D6 rejeitar proposta legítima de valor pequeno | 5 | Teste com `celular_10x` e `divisao_inexata`; se falhar, revisar a tolerância no PRD (nova versão) |
| `requiresChannel` deprecado no Spring Security 7 | 7 | Filtro próprio (T4), independente da API deprecada |
| `X-Forwarded-For` forjado se a app ficar exposta sem proxy | 8 | Só o perfil `prod` confia nos headers; a implantação pública exige o Caddy (PRD futuro) |
| Teste de tempo de resposta instável na CI | 8 | Comparar medianas com tolerância larga; marcar como `@Tag("lento")` se necessário |
| A string decimal na API diverge do exemplo numérico da doc 02 | 5, 6 | Decisão T2 registrada; o app Kotlin segue este contrato |

## 8. Rastreabilidade PRD → SPEC

| Requisito | Fase | Observação |
|---|---|---|
| RF01, RF02, RF03 | 2 | — |
| RF04 | 1 | `ModularidadeTest` |
| RF05 | 1 | Tabela de eventos via V001 |
| RF06 | 2 | Atualizado na 7 (401 e 403 no formato) |
| RF07 | 1 | — |
| RF08 | 1 | — |
| RF10–RF14 | 3 | — |
| RF15 | 4 | Fórmula D7 |
| RF16 | 4 | Semântica definida na decisão T7 |
| RF17 | 3 | Ampliado na 4 |
| RF20, RF21 | 5 | — |
| RF22 | 6 | D4 |
| RF23, RF24 | 6 | D3 para o DELETE |
| RF25, RF26, RF27, RF28 | 5 | D6 |
| RF30–RF34, RF36 | 7 | — |
| RF35, RF37 | 8 | — |
| RNF01–RNF06, RNF11, RNF12 | 1 | — |
| RNF07 | 3 | Teste de desempenho |
| RNF08 | 7 | — |
| RNF09, RNF10 | 1 (infra), 2–8 (uso) | — |
| RNF13 | — | Requisito de produção; validado no PRD de implantação |
| RN01–RN03 | 2 | — |
| RN04–RN07 | 3 | — |
| RN08 | 5 | Nomenclatura nos DTOs/mensagens ("taxa efetiva das parcelas") |
| RN09–RN14 | 5 | RN12 também na 6 |
| RN15 | 7 (senha inicial), 8 (troca) | — |
| RN16–RN18 | 8 | — |
| RN19 | 7 | — |
| SEC01–SEC05 | 7 (SEC05 também na 1) | — |
| SEC06 | 2 | — |
| SEC07 | 7, 8 | — |
| SEC08 | 8 | — |
| SEC09 | 1 (exposição), 7 (autenticação) | — |
| SEC10 | 1 | Compose sem porta do Postgres |
| SEC11, SEC12 | 7 | D1, T4 |
| D1–D8 | 7, 5, 6, 6, 3, 5, 4, 3 | Respectivamente |

### Decisões técnicas deste SPEC

| # | Decisão | Motivo |
|---|---|---|
| T1 | Módulo `plataforma` (OPEN) para web e Jackson | Mantém `comum` sem Spring (regra 1 da doc 00) |
| T2 | Dinheiro e taxa como string decimal no JSON; taxa como fração | Evita `Double` no cliente; mesmo formato dos vetores |
| T3 | `DELETE` de ID inexistente → 204 | Idempotência, coerente com a fila offline |
| T4 | Filtro próprio rejeita HTTP com 403, sem redirecionar | Redirecionar depois de receber a senha em HTTP não protege nada; não depende de API deprecada |
| T5 | Tentativa bloqueada não é registrada | Evita bloqueio perpétuo de quem continua tentando |
| T6 | jqwik para testes de propriedade | Integração nativa com JUnit 5 |
| T7 | Curva de R$ 0 ao valor do bem; distância à âncora como fração do valor do bem | Mesma escala para qualquer bem; o ponto à vista é o alvo do usuário, não erro |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|---|---|---|
| 1.0 | 2026-11-07 | Criação inicial: 8 fases |
| 1.1 | 2026-11-07 | Fase 1 concluída: Boot 4.1.2, Modulith 2.1.1 (schema de eventos v2), Gradle 9.8.2; segurança provisória `permitAll` em `auth` até a Fase 7 |
| 1.2 | 2026-11-07 | Fase 2 concluída: Jackson 3 (`tools.jackson`) do Boot 4; erros do próprio Spring MVC (404, 405, 415, parâmetro ausente) mantêm o status no formato `ErroApi`; regra ArchUnit garante `comum` sem Spring/Jackson |
| 1.3 | 2026-11-07 | Fase 3 concluída. Acréscimos: `ValorInvalidoException` (`VALOR_INVALIDO`) para entradas degeneradas, já que a RN04 pedia "erro de validação" sem nomear a exceção; taxa **abaixo** de 1e-6 também gera `TaxaForaDaFaixaException` (com `detalhes.limite` = MINIMO ou MAXIMO); `n = 1` usa a solução fechada `PMT/PV − 1`, com a mesma faixa; a bisseção para por \|VP − PV\| < 1e-10 **ou** largura do intervalo < 1e-12 (o DECIMAL64 limita a precisão do valor para PV grande); `parcelaExata`/`taxaImplicitaExata` são package-private para a propriedade de precisão total; segunda propriedade: a taxa achada a partir da parcela em centavos reproduz a mesma parcela; regra ArchUnit: `core` só depende de `java..` e `comum` |
| 1.4 | 2026-11-07 | Decisão T7 (semântica da curva) aprovada; Fase 4 concluída. Acréscimos: no `Comparador`, taxa abaixo de 0,0001% a.m. vira `ZERO` (acima de 30% o erro se propaga); conveniências `Veredito.aVistaVence()` e `PontoSensibilidade.ancora()`; 4 vetores de comparação calculados de forma independente |
| 1.5 | 2026-11-07 | Fase 5 concluída. Desvios: (a) **tolerância de R$ 0,01 na parcela da estimativa** em vez de exata (D6): o app calcula com a taxa da âncora em precisão total e o servidor só tem 8 casas, e uma rejeição indevida travaria a fila offline, que não descarta itens; (b) inserção com `INSERT … ON CONFLICT (id) DO NOTHING` via `JdbcClient` em vez de `JdbcAggregateTemplate.insert` + captura de PK (sem transação abortada na corrida); (c) o trigger também protege `valor_financiado` e `taxa_anual`, que são snapshot; (d) proposta com `ancoraId` → `ANCORA_INVALIDA`; âncora de estimativa precisa ser `PROPOSTA`; (e) DTOs e erros no pacote `historico`, sem subpacotes `dto/` e `erros/` (tudo package-private) |
| 1.6 | 2026-11-07 | Fase 6 concluída. Detalhes: `PATCH` lê o corpo como mapa (campo ausente não muda; `observacao: null` ou em branco apaga); `status: null` → 422; a resposta da comparação também traz `local`, `origem`, `status` e `parcela` de cada item; `DELETE` captura a violação de FK de uma estimativa gravada no meio da exclusão e responde 409; `economiaEntreMelhorEPior` pode ser **negativa** quando a proposta de menor taxa tem prazo maior (juros totais maiores), o que é coerente com a D4 |
| 1.7 | 2026-11-07 | Fase 7 concluída. Desvios: (a) o seed lê a propriedade `app.senha-inicial`, preenchida por `APP_SENHA_INICIAL` (variável de ambiente não é configurável em teste; para o usuário, nada muda); (b) **`/actuator/health` fica fora da exigência de HTTPS**, porque o healthcheck interno chama por HTTP e a rota só responde "UP"; (c) `HttpsObrigatorioFilter` e `TokenFilter` não são beans (só existem dentro da cadeia de segurança, sem registro duplicado como filtro de servlet); (d) 401 traz `WWW-Authenticate: Bearer`; 403 de autorização sai como `ACESSO_NEGADO`; (e) datas da listagem de dispositivos no fuso de São Paulo, como no histórico; (f) alvo `make dispositivo`; (g) nos testes, `ClienteDeTeste` grava um dispositivo direto no banco e usa HTTPS, e `src/test/resources/config/application.yml` define a senha inicial dos testes. Validado de ponta a ponta com Docker Compose |
| 1.8 | 2026-11-14 | Fase 8 concluída; fim deste SPEC. Desvios e detalhes: (a) **bean `Clock relogio()` no `SecurityConfig`**, arquivo fora da lista da fase, para o limite usar um relógio injetado (os testes trocam por `RelogioAjustavel`, `@Primary`); (b) `detalhes.liberaEm` = horário da 5ª falha mais recente do IP + 15 min, no fuso de São Paulo (ISO-8601 com offset); `LOGIN_BLOQUEADO_GLOBAL` não traz detalhes; (c) o hash fictício do RN18 é gerado no construtor do `DispositivoService` a partir de um UUID aleatório, com o mesmo custo do encoder; (d) `cadastrar` usa `@Transactional(noRollbackFor = CredenciaisInvalidasException.class)`, para a linha da falha não ser desfeita junto com a exceção; (e) `senhaAtual` ausente ou em branco → 422 de validação, `senhaAtual` errada → 401 `CREDENCIAIS_INVALIDAS`; troca bem-sucedida → 204, e `SENHA_CURTA` traz `detalhes.minimo`; (f) `UsuarioRepository` ganhou `buscarUnico()` e `atualizarSenha()`, e `DispositivoRepository` ganhou `revogarTodosExceto()`; (g) red refeito com Docker: as 10 falhas foram de asserção (200 em vez de 429, 404 em `/api/auth/senha`, medianas de 2 ms contra 191 ms). (h) alvo `make senha`. Suíte completa: 217 testes, 0 falhas. Critérios de aceitação do PRD (seção 10) marcados |
