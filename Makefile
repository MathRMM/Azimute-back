# =============================================================================
# AZIMUTE CORE — backend do assistente pessoal
# =============================================================================
# Requer: JDK 25 (SDKMAN), Docker com Compose v2. Variáveis lidas do .env.

GRADLE := ./gradlew
# Bash, não o /bin/sh padrão: no Ubuntu ele é o dash, que não tem "read -s" (senha e token sem eco) nem "read -p".
SHELL := /bin/bash

.PHONY: help setup dev start stop test test-watch test-unit test-integracao coverage dispositivo senha docs \
        conta transferir conciliar consolidado \
        migrate seed build deploy \
        docker-up docker-down docker-logs docker-up-hml docker-up-prod

help:
	@echo ""
	@echo "  Comandos disponíveis:"
	@echo ""
	@echo "  Setup"
	@echo "    make setup          Baixa dependências e cria o .env a partir do .env.example"
	@echo ""
	@echo "  Desenvolvimento"
	@echo "    make dev            Sobe app + postgres no perfil dev (docker compose)"
	@echo "    make dispositivo    Cadastra um dispositivo e mostra o token (uma vez)"
	@echo "    make senha          Troca a senha; revoga os outros dispositivos (o do token continua)"
	@echo "    make docs           Mostra onde abrir o Swagger UI (só no perfil dev, sem token)"
	@echo ""
	@echo "  Contas (pedem o token do dispositivo, sem eco)"
	@echo "    make conta          Cadastra uma conta com o saldo de hoje"
	@echo "    make transferir     Transfere entre duas contas (IDs de make consolidado)"
	@echo "    make conciliar      Informa o saldo real de uma conta e mostra a divergência"
	@echo "    make consolidado    Mostra a posição geral, o saldo em conta, o bloqueado (FGTS e previdência) e cada conta"
	@echo "    make start          Sobe app + postgres no perfil prod (docker compose)"
	@echo "    make stop           Para os containers"
	@echo ""
	@echo "  Testes"
	@echo "    make test           Executa todos os testes e exige 90% de linhas e de ramos (JaCoCo)"
	@echo "    make test-unit      Executa só os testes sem banco (*Test)"
	@echo "    make test-integracao Executa só os testes com Postgres via Testcontainers (*IT)"
	@echo "    make coverage       Mostra onde ficam os relatórios de cobertura (completo e parcial)"
	@echo "    make test-watch     Reexecuta os testes a cada mudança"
	@echo ""
	@echo "  Banco de dados"
	@echo "    make migrate        Migrations rodam na subida da app (Flyway)"
	@echo "    make seed           Explica como o usuário inicial é criado"
	@echo ""
	@echo "  Build e Deploy"
	@echo "    make build          Gera o jar (testes inclusos)"
	@echo "    make deploy         [TODO] Sem VPS definida ainda"
	@echo ""
	@echo "  Docker"
	@echo "    make docker-up      Sobe containers (local, .env)"
	@echo "    make docker-down    Derruba containers"
	@echo "    make docker-logs    Exibe logs dos containers"
	@echo "    make docker-up-hml  [TODO] Sem ambiente de homologação"
	@echo "    make docker-up-prod [TODO] Sem VPS definida ainda"
	@echo ""

setup:
	@test -f .env || (cp .env.example .env && echo ".env criado — preencha DB_SENHA")
	$(GRADLE) dependencies > /dev/null

dev:
	SPRING_PROFILES_ACTIVE=dev docker compose --env-file .env up -d --build

start:
	SPRING_PROFILES_ACTIVE=prod docker compose --env-file .env up -d --build

stop:
	docker compose down

test:
	$(GRADLE) test jacocoTestCoverageVerification

test-unit:
	$(GRADLE) test --tests '*Test'

test-integracao:
	$(GRADLE) test --tests '*IT'

coverage:
	@echo "Suíte completa (make test):        build/reports/jacoco/test/html/index.html"
	@echo "Execução parcial (test-unit/-integracao, --tests): build/reports/jacoco/parcial/html/index.html"

test-watch:
	$(GRADLE) test --continuous

migrate:
	@echo "Migrations são aplicadas pelo Flyway na subida da aplicação (make dev)."

seed:
	@echo "O usuário 'admin' é criado no primeiro boot a partir de APP_SENHA_INICIAL (.env, mín. 12 caracteres)."
	@echo "Sem usuário e sem a variável, a aplicação não sobe. Depois do primeiro boot, remova a variável."

dispositivo:
	@read -p "Senha do admin: " -s SENHA; echo; \
	read -p "Nome do dispositivo: " NOME; \
	curl -s -X POST http://localhost:8080/api/auth/dispositivos -H 'Content-Type: application/json' \
	  -d "{\"login\":\"admin\",\"senha\":\"$$SENHA\",\"nomeDispositivo\":\"$$NOME\"}"; echo

senha:
	@read -p "Token do dispositivo que continua ativo: " -s TOKEN; echo; \
	read -p "Senha atual: " -s ATUAL; echo; \
	read -p "Senha nova (mín. 12 caracteres): " -s NOVA; echo; \
	curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/auth/senha \
	  -H "Authorization: Bearer $$TOKEN" -H 'Content-Type: application/json' \
	  -d "{\"senhaAtual\":\"$$ATUAL\",\"senhaNova\":\"$$NOVA\"}"

API := http://localhost:8080/api/contas

conta:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Apelido: " APELIDO; \
	read -p "Instituição: " INSTITUICAO; \
	read -p "Tipo (CORRENTE, POUPANCA, INVESTIMENTO, CARTEIRA, BENEFICIO, FGTS, PREVIDENCIA): " TIPO; \
	read -p "Função (GIRO, RESERVA): " FUNCAO; \
	read -p "Saldo de hoje (ex.: 2300.00): " SALDO; \
	read -p "Limite de cheque especial (só corrente, Enter para nenhum): " LIMITE; \
	LIMITE_JSON=$$( [ -n "$$LIMITE" ] && echo ",\"limiteChequeEspecial\":\"$$LIMITE\"" ); \
	curl -s -w "\nHTTP %{http_code}\n" -X POST $(API) -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' \
	  -d "{\"apelido\":\"$$APELIDO\",\"instituicao\":\"$$INSTITUICAO\",\"tipo\":\"$$TIPO\",\"funcao\":\"$$FUNCAO\",\"saldoInicial\":\"$$SALDO\"$$LIMITE_JSON}"

transferir:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "ID da conta de origem: " ORIGEM; \
	read -p "ID da conta de destino: " DESTINO; \
	read -p "Valor (ex.: 1500.00): " VALOR; \
	read -p "Data (AAAA-MM-DD): " DATA; \
	read -p "Motivo (opcional): " MOTIVO; \
	ID=$$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen); \
	curl -s -w "\nHTTP %{http_code}\n" -X PUT $(API)/movimentacoes/$$ID -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' \
	  -d "{\"tipo\":\"TRANSFERENCIA\",\"origem\":\"$$ORIGEM\",\"destino\":\"$$DESTINO\",\"valor\":\"$$VALOR\",\"data\":\"$$DATA\",\"motivo\":\"$$MOTIVO\"}"

conciliar:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "ID da conta: " CONTA; \
	read -p "Data do extrato (AAAA-MM-DD): " DATA; \
	read -p "Saldo real no extrato: " SALDO; \
	read -p "Justificativa (opcional): " JUSTIFICATIVA; \
	curl -s -w "\nHTTP %{http_code}\n" -X POST $(API)/$$CONTA/conciliacoes -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' \
	  -d "{\"data\":\"$$DATA\",\"saldoReal\":\"$$SALDO\",\"justificativa\":\"$$JUSTIFICATIVA\"}"

consolidado:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	curl -s -w "\nHTTP %{http_code}\n" $(API)/consolidado -H "Authorization: Bearer $$TOKEN"

docs:
	@echo "Swagger UI: http://localhost:8080/swagger-ui.html (perfil dev; suba com make dev)"
	@echo "OpenAPI:    http://localhost:8080/v3/api-docs"

build:
	$(GRADLE) build

deploy:
	@echo "[TODO] Comando de deploy não configurado ainda (sem VPS definida)."

docker-up:
	docker compose --env-file .env up -d --build

docker-down:
	docker compose down

docker-logs:
	docker compose logs -f

docker-up-hml:
	@echo "[TODO] Ambiente de homologação não existe."

docker-up-prod:
	@echo "[TODO] Implantação pública depende do PRD de implantação (VPS, Caddy)."
