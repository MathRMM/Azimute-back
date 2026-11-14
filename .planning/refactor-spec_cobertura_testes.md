# REFACTOR-SPEC — Cobertura de testes (unitário + integração, 90%)

**Projeto:** Azimute Core (`backend-azimute-core`)
**Data:** 2026-11-14
**Status:** aprovado e executado
**Objetivo:** adicionar testes (sem refatorar código de produção)

---

## 1. Estado antes

| Item | Valor |
|---|---|
| JaCoCo | não configurado |
| Testes | 217 passando, 0 falhando (11 classes `*Test`, 9 classes `*IT`) |
| Cobertura (unit + IT) | 95,8% linhas, 87,4% ramos |
| Cobertura (só unit) | 30,0% linhas; `auth` e `calculadora/historico` com 0% |

## 2. Camadas

| Camada | Sufixo | Stack | Escopo |
|---|---|---|---|
| Unitário | `*Test` | JUnit 5, AssertJ, Mockito, jqwik | regras de negócio com repositórios falsos e relógio fixo; kernel; tradução de erros |
| Integração | `*IT` | Spring Boot Test, Testcontainers (Postgres 18), MockMvc | HTTP + segurança + SQL + migrations, ponta a ponta |

Não há camada E2E separada: os `*IT` já entram por HTTP com a cadeia de segurança real.

## 3. Meta e trava

- **90% de linhas e 90% de ramos**, medidos com unitários e integração juntos (`jacocoTestCoverageVerification`).
- Roda no `./gradlew check` (e portanto no `build`) e no `make test`. Abaixo da meta, o build falha.
- Fora da medição: só `AzimuteApplication` (o `main`).
- A cobertura só dos unitários é acompanhada, mas não travada: repositórios JDBC, controllers e configuração do Spring são cobertos pelos `*IT`, e testá-los com mocks não protegeria nada.

## 4. Testes adicionados (79)

| Arquivo | Cobre |
|---|---|
| `auth/LimiteDeTentativasTest` | janelas de 15 min e 1 h, `liberaEm` no fuso de São Paulo, relógio injetado (RN16, RN17) |
| `auth/DispositivoServiceTest` | ordem limite → BCrypt → registro; hash fictício (RN18); só o hash do token é gravado |
| `auth/SenhaServiceTest` | senha atual errada, 11 × 12 caracteres, revoga todos menos o atual (RF35, RN15) |
| `auth/SeedUsuarioTest` | senha inicial ausente, em branco ou curta impede a subida (RF30, SEC04) |
| `auth/FiltrosDeSegurancaTest` | `TokenFilter` e `HttpsObrigatorioFilter` sem subir o Spring (RF32, SEC12) |
| `auth/GeradorDeTokenTest` | formato do token e vetor SHA-256 do FIPS 180-2 |
| `historico/HistoricoServiceTest` | RN10, RN11, idempotência (D2), exclusão (T3, D3), ordenação da comparação (D4) |
| `historico/ConferenciaDeSnapshotTest` | divergências de totais e taxas; tolerância de 1 centavo da estimativa (D6) |
| `historico/MontadorDeSimulacao` | apoio: simulação consistente de R$ 1.000 a 1% a.m. |
| `core/BordasDoCoreTest` | prazo e valor negativos, taxa abaixo do mínimo e acima do máximo no comparador (D5) |
| `comum/BordasDoKernelTest` | competência nula, `Percentual.toString`, erro sem detalhes |
| `plataforma/web/TradutorDeErrosBordasTest` | erro global, validação de parâmetro, 404/406/415/outros, Dinheiro inteiro no JSON |

## 5. Resultado

| Medição | Linhas | Ramos |
|---|---|---|
| Unit + integração | **99,3%** (757/762) | **97,7%** (255/261) |
| Só unitários | 67,6% | 79,7% |

O que sobra sem cobertura é inalcançável por construção: o `catch` de `NoSuchAlgorithmException` do SHA-256 e a saída por esgotamento da bisseção (`NaoConvergiuException`), que não ocorre dentro da faixa de taxas.

Verificado: a trava falha com só os unitários (`lines covered ratio is 0.67, but expected minimum is 0.90`).

## 6. Makefile

- `make test`: todos os testes + verificação de cobertura.
- `make test-unit` e `make test-integracao`: cada camada separada (sem trava).
- `make coverage`: caminho do relatório HTML.

## Histórico de Revisões

| Versão | Data | Alteração |
|---|---|---|
| 1.0 | 2026-11-14 | Criação e execução: JaCoCo, 79 testes, trava de 90% em linhas e ramos |
