# =============================================================================
# AZIMUTE CORE — backend do assistente pessoal
# =============================================================================
# Requer: JDK 25 (SDKMAN), Docker com Compose v2. Variáveis lidas do .env.

GRADLE := ./gradlew

.PHONY: help setup dev start stop test test-watch test-unit \
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
	@echo "    make start          Sobe app + postgres no perfil prod (docker compose)"
	@echo "    make stop           Para os containers"
	@echo ""
	@echo "  Testes"
	@echo "    make test           Executa todos os testes (unitários + integração com Testcontainers)"
	@echo "    make test-unit      Executa só os testes sem banco (*Test)"
	@echo "    make test-watch     Reexecuta os testes a cada mudança"
	@echo ""
	@echo "  Banco de dados"
	@echo "    make migrate        Migrations rodam na subida da app (Flyway)"
	@echo "    make seed           [TODO] Seed do usuário inicial chega na Fase 7"
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
	$(GRADLE) test

test-unit:
	$(GRADLE) test --tests '*Test'

test-watch:
	$(GRADLE) test --continuous

migrate:
	@echo "Migrations são aplicadas pelo Flyway na subida da aplicação (make dev)."

seed:
	@echo "[TODO] Seed do usuário inicial chega na Fase 7 (APP_SENHA_INICIAL)."

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
