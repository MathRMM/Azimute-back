# =============================================================================
# AZIMUTE CORE — backend do assistente pessoal
# =============================================================================
# Requer: JDK 25 (SDKMAN), Docker com Compose v2. Variáveis lidas do .env.

GRADLE := ./gradlew
# Bash, não o /bin/sh padrão: no Ubuntu ele é o dash, que não tem "read -s" (senha e token sem eco) nem "read -p".
SHELL := /bin/bash

.PHONY: help setup dev start stop test test-watch test-unit test-integracao coverage dispositivo senha docs \
        conta transferir conciliar consolidado categorias categoria gasto gastos ganho ganhos \
        folha-configurar adiantamento holerite holerite-manual holerites resumo resumo-folha \
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
	@echo ""
	@echo "  Fluxo (pedem o token do dispositivo, sem eco)"
	@echo "    make categorias     Lista as categorias de gasto ativas, com os IDs"
	@echo "    make categoria      Cria uma categoria de gasto"
	@echo "    make gasto          Registra um gasto à vista (sai da conta escolhida)"
	@echo "    make gastos         Lista os gastos de uma competência (AAAA-MM)"
	@echo "    make ganho          Registra um ganho avulso já recebido (PLR, FREELA, REEMBOLSO, OUTRO)"
	@echo "    make ganhos         Lista os ganhos de uma competência, com os recebimentos"
	@echo "    make resumo         Resumo do mês: gasto por categoria (com a média de 3 meses), recebido e sobra"
	@echo ""
	@echo "  Folha (pedem o token do dispositivo, sem eco)"
	@echo "    make folha-configurar Define as contas do salário, do FGTS e da previdência"
	@echo "    make adiantamento   Lança o adiantamento antes de o holerite chegar"
	@echo "    make holerite       Envia o PDF do holerite (caminho do arquivo; datas opcionais)"
	@echo "    make holerite-manual Envia um holerite em JSON (padrão: exemplos/holerite-manual.json, fictício)"
	@echo "    make holerites      Lista os holerites de um ano"
	@echo "    make resumo-folha   Resumo anual da folha: impostos, previdência, FGTS e líquido, por mês e no ano"
	@echo ""
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

API_FLUXO := http://localhost:8080/api

categorias:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	curl -s -w "\nHTTP %{http_code}\n" $(API_FLUXO)/categorias -H "Authorization: Bearer $$TOKEN"

categoria:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Nome: " NOME; \
	curl -s -w "\nHTTP %{http_code}\n" -X POST $(API_FLUXO)/categorias -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' -d "{\"nome\":\"$$NOME\"}"

gasto:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "ID da conta (de make consolidado): " CONTA; \
	read -p "ID da categoria (de make categorias): " CATEGORIA; \
	read -p "Descrição: " DESCRICAO; \
	read -p "Valor (ex.: 120.00): " VALOR; \
	read -p "Data (AAAA-MM-DD, Enter para hoje): " DATA; \
	DATA=$${DATA:-$$(date +%F)}; \
	ID=$$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen); \
	curl -s -w "\nHTTP %{http_code}\n" -X PUT $(API_FLUXO)/gastos/$$ID -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' \
	  -d "{\"data\":\"$$DATA\",\"descricao\":\"$$DESCRICAO\",\"valor\":\"$$VALOR\",\"categoria\":\"$$CATEGORIA\",\"conta\":\"$$CONTA\"}"

gastos:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Competência (AAAA-MM, Enter para o mês atual): " COMPETENCIA; \
	COMPETENCIA=$${COMPETENCIA:-$$(date +%Y-%m)}; \
	curl -s -w "\nHTTP %{http_code}\n" "$(API_FLUXO)/gastos?competencia=$$COMPETENCIA" \
	  -H "Authorization: Bearer $$TOKEN"

ganho:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "ID da conta que recebeu (de make consolidado): " CONTA; \
	read -p "Tipo (PLR, FREELA, REEMBOLSO, OUTRO): " TIPO; \
	read -p "Descrição: " DESCRICAO; \
	read -p "Bruto (ex.: 1000.00): " BRUTO; \
	read -p "Líquido recebido (Enter para igual ao bruto): " LIQUIDO; \
	LIQUIDO=$${LIQUIDO:-$$BRUTO}; \
	read -p "Data do recebimento (AAAA-MM-DD, Enter para hoje): " DATA; \
	DATA=$${DATA:-$$(date +%F)}; \
	read -p "Competência (AAAA-MM, Enter para o mês do recebimento): " COMPETENCIA; \
	COMPETENCIA=$${COMPETENCIA:-$${DATA:0:7}}; \
	ID=$$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen); \
	curl -s -w "\nHTTP %{http_code}\n" -X PUT $(API_FLUXO)/ganhos/$$ID -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' \
	  -d "{\"competencia\":\"$$COMPETENCIA\",\"descricao\":\"$$DESCRICAO\",\"tipo\":\"$$TIPO\",\"bruto\":\"$$BRUTO\",\"liquido\":\"$$LIQUIDO\",\"conta\":\"$$CONTA\",\"recebidoEm\":\"$$DATA\"}"

ganhos:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Competência (AAAA-MM, Enter para o mês atual): " COMPETENCIA; \
	COMPETENCIA=$${COMPETENCIA:-$$(date +%Y-%m)}; \
	curl -s -w "\nHTTP %{http_code}\n" "$(API_FLUXO)/ganhos?competencia=$$COMPETENCIA" \
	  -H "Authorization: Bearer $$TOKEN"

folha-configurar:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "ID da conta do salário: " SALARIO; \
	read -p "ID da conta FGTS: " FGTS; \
	read -p "ID da conta de previdência: " PREVIDENCIA; \
	curl -s -w "\nHTTP %{http_code}\n" -X PUT $(API_FLUXO)/folha/configuracao -H "Authorization: Bearer $$TOKEN" \
	  -H 'Content-Type: application/json' \
	  -d "{\"contaSalario\":\"$$SALARIO\",\"contaFgts\":\"$$FGTS\",\"contaPrevidencia\":\"$$PREVIDENCIA\"}"

adiantamento:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Competência (AAAA-MM, Enter para o mês atual): " COMPETENCIA; \
	COMPETENCIA=$${COMPETENCIA:-$$(date +%Y-%m)}; \
	read -p "Valor (ex.: 2500.00): " VALOR; \
	read -p "Data (AAAA-MM-DD, Enter para o dia 15 ou o dia útil anterior): " DATA; \
	DATA_JSON=$$( [ -n "$$DATA" ] && echo ",\"data\":\"$$DATA\"" ); \
	ID=$$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen); \
	curl -s -w "\nHTTP %{http_code}\n" -X PUT $(API_FLUXO)/folha/adiantamentos/$$ID \
	  -H "Authorization: Bearer $$TOKEN" -H 'Content-Type: application/json' \
	  -d "{\"competencia\":\"$$COMPETENCIA\",\"valor\":\"$$VALOR\"$$DATA_JSON}"

holerite:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Caminho do PDF do holerite: " ARQUIVO; \
	test -f "$$ARQUIVO" || { echo "Arquivo não encontrado: $$ARQUIVO"; exit 1; }; \
	read -p "Data do adiantamento (AAAA-MM-DD, Enter para a padrão): " ADIANTAMENTO; \
	read -p "Data do líquido (AAAA-MM-DD, Enter para a padrão): " LIQUIDO; \
	curl -s -w "\nHTTP %{http_code}\n" -X POST $(API_FLUXO)/folha/holerites -H "Authorization: Bearer $$TOKEN" \
	  -F "arquivo=@$$ARQUIVO;type=application/pdf" \
	  $${ADIANTAMENTO:+-F "dataAdiantamento=$$ADIANTAMENTO"} $${LIQUIDO:+-F "dataLiquido=$$LIQUIDO"}

holerites:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Ano (Enter para o atual): " ANO; \
	ANO=$${ANO:-$$(date +%Y)}; \
	curl -s -w "\nHTTP %{http_code}\n" "$(API_FLUXO)/folha/holerites?ano=$$ANO" -H "Authorization: Bearer $$TOKEN"

resumo:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Competência (AAAA-MM, Enter para o mês atual): " COMPETENCIA; \
	COMPETENCIA=$${COMPETENCIA:-$$(date +%Y-%m)}; \
	curl -s -w "\nHTTP %{http_code}\n" "$(API_FLUXO)/fluxo/resumo?competencia=$$COMPETENCIA" \
	  -H "Authorization: Bearer $$TOKEN"

resumo-folha:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Ano (Enter para o atual): " ANO; \
	ANO=$${ANO:-$$(date +%Y)}; \
	curl -s -w "\nHTTP %{http_code}\n" "$(API_FLUXO)/folha/resumo?ano=$$ANO" -H "Authorization: Bearer $$TOKEN"

holerite-manual:
	@read -p "Token do dispositivo: " -s TOKEN; echo; \
	read -p "Arquivo JSON (Enter para exemplos/holerite-manual.json): " ARQUIVO; \
	ARQUIVO=$${ARQUIVO:-exemplos/holerite-manual.json}; \
	test -f "$$ARQUIVO" || { echo "Arquivo não encontrado: $$ARQUIVO"; exit 1; }; \
	curl -s -w "\nHTTP %{http_code}\n" -X POST $(API_FLUXO)/folha/holerites/manual \
	  -H "Authorization: Bearer $$TOKEN" -H 'Content-Type: application/json' --data-binary "@$$ARQUIVO"

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
