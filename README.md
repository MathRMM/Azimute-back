# Azimute Core

Backend do assistente pessoal Azimute: contador, treinador e secretária, num monólito modular.

- **Stack:** Java 25 · Spring Boot 4.1 · Spring Modulith 2.1 · Spring Data JDBC · PostgreSQL 18 · Flyway
- **Documentação de arquitetura:** [`assistente-docs/`](assistente-docs/README.md)
- **Planejamento:** [`.planning/`](.planning/) (PRD, SPEC e issues)

## Requisitos

- JDK 25 (`sdk install java 25.0.5.0+8-tem`); o Gradle baixa sozinho se não houver
- Docker com Compose v2 (no WSL: Docker Desktop com a integração da distro ativa)

## Primeiros passos

```bash
make setup        # cria o .env a partir do .env.example — preencha DB_SENHA e APP_SENHA_INICIAL
make test         # testes unitários + integração (Testcontainers, postgres:18)
make dev          # sobe app + postgres em http://localhost:8080
make dispositivo  # troca a senha do admin por um token deste dispositivo (mostrado uma vez só)
curl localhost:8080/api/calculadora/historico -H "Authorization: Bearer <token>"
make stop
```

`make help` lista todos os comandos.

## Variáveis de ambiente

| Variável | Uso |
|---|---|
| `DB_NOME`, `DB_USUARIO`, `DB_SENHA` | Banco (compose e aplicação) |
| `DB_URL` | URL JDBC; o compose monta sozinho |
| `SPRING_PROFILES_ACTIVE` | `dev` (local, HTTP aceito) ou `prod` (HTTPS exigido, log JSON). Sem perfil, valem as regras seguras |
| `APP_SENHA_INICIAL` | Senha do usuário `admin` (mín. 12 caracteres). **Obrigatória no primeiro boot**: sem usuário e sem ela, a aplicação não sobe. Remova depois |

Segredos ficam só no `.env`, que nunca é versionado.

## Autenticação

- `POST /api/auth/dispositivos` com `{ login, senha, nomeDispositivo }` devolve um token **uma única vez**; o banco guarda só o SHA-256 dele.
- Toda requisição usa `Authorization: Bearer <token>`. `GET /api/auth/dispositivos` lista os aparelhos; `DELETE /api/auth/dispositivos/{id}` revoga na hora.
- Fora do perfil `dev`, HTTP puro é rejeitado com 403 (atrás do proxy, vale o `X-Forwarded-Proto`). `/actuator/health` é a única rota pública além do cadastro.

## Módulos

Pacote raiz `br.dev.azimute`, um pacote por módulo. As fronteiras são verificadas por `ModularidadeTest`.

| Módulo | Estado |
|---|---|
| `comum` | Kernel de tipos de valor (Fase 2) |
| `plataforma` | Erros e Jackson (Fase 2) |
| `calculadora` | `core` e `historico` (Fases 3–6) |
| `auth` | Senha uma vez, token por dispositivo; HTTPS exigido fora do perfil `dev` |
| `contas`, `fluxo`, `cartoes`, `reserva`, `treino`, `saude`, `sistema`, `agenda`, `integracao` | Reservados para o roadmap |

## Banco

- Um schema por módulo; o schema é definido só pelas migrations (`src/main/resources/db/migration`), nunca por `ddl-auto`.
- O Postgres não é exposto no host. Para acessar pela IDE, crie um `compose.override.yaml` local (ignorado pelo git):

```yaml
services:
  postgres:
    ports: ["127.0.0.1:5432:5432"]
```
