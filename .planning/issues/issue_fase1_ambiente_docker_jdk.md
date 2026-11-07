# Issue — Fase 1: Docker inacessível no WSL e JDK 25 ausente

**Data:** 2026-11-07
**Fase afetada:** Fase 1 — Esqueleto do projeto
**Status:** resolvido

---

## Problema identificado

1. O comando `docker` não existe dentro da distro WSL `Ubuntu` (24.04). O Docker Desktop **está instalado e rodando** no Windows (cliente e servidor 29.7.2, acessível via `docker.exe`), mas a integração com essa distro está desligada.
2. O único JDK instalado é o OpenJDK 17.0.20. O SPEC exige Java 25 (RNF01). O SDKMAN está instalado, mas sem nenhum candidato `java`.

## Contexto

O SPEC aprovado foi commitado, e as branches `feature/fundacao-calculadora-auth` (pai) e `feature/fundacao-calculadora-auth-fase-1` (filha) foram criadas a partir de `main`. Nenhum código da Fase 1 foi escrito.

A Fase 1 exige:
- testes de integração com Testcontainers (`postgres:18`): o `FundacaoIT` e, a partir da Fase 5, todos os testes de persistência (RNF09);
- `docker compose up` subindo app + postgres (RNF11);
- build com toolchain Java 25 (RNF01).

## Evidência

```
$ docker --version
The command 'docker' could not be found in this WSL 2 distro.
We recommend to activate the WSL integration in Docker Desktop settings.

$ docker.exe version --format '{{.Client.Version}} / server {{.Server.Version}}'
29.7.2 / server 29.7.2

$ java -version
openjdk version "17.0.20.1" 2026-08-18

$ ls ~/.sdkman/candidates/java
(vazio)
```

## Impacto

- Sem Docker no WSL, a etapa red/green da Fase 1 não pode ser executada: o `FundacaoIT` não sobe o container, e o checklist "`docker compose up` sobe app + postgres" não é verificável.
- Todas as fases seguintes que tocam o banco (5, 6, 7 e 8) ficam bloqueadas pelo mesmo motivo.
- Sem o JDK 25 local, o build depende de a toolchain automática do Gradle baixar o JDK, e a IDE não enxerga o Java 25.

## Hipóteses

- Hipótese A: a integração WSL do Docker Desktop nunca foi ativada para a distro `Ubuntu`.
- Hipótese B: ela foi ativada para outra distro (a padrão) e não para esta.

## Opções de resolução

| Opção | Descrição | Trade-off |
|---|---|---|
| A (recomendada) | Docker Desktop → Settings → Resources → WSL Integration → ativar `Ubuntu` → Apply & Restart. Depois, no terminal: `sdk install java 25-tem` | Mudança única de configuração; o ambiente fica igual ao previsto no SPEC |
| B | Instalar o Docker Engine nativo dentro do WSL (sem o Desktop) | Duas instalações de Docker na mesma máquina, com risco de conflito de portas |
| C | Seguir a Fase 1 sem Docker: escrever tudo e validar só a compilação e o `ModularidadeTest` | Viola a ordem TDD da skill (os testes de integração não podem ficar vermelhos e depois verdes); checklist incompleto |

## Arquivos envolvidos

- `.planning/spec_fundacao_calculadora_auth.md` — Fase 1, seção 7 (risco já previsto: "Docker indisponível no WSL")

---

## Resolução (2026-11-07)

- Docker: a integração WSL do Docker Desktop foi ativada pelo usuário; `docker` 29.7.2 e Compose v5.5.0 funcionando dentro da distro (verificado com `postgres:18`).
- JDK: Temurin 25.0.5 LTS instalado via SDKMAN e definido como padrão.
