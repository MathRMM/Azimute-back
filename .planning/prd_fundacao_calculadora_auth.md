# PRD — Azimute Core: Fundação, Calculadora e Auth (Fases 1–2)

**Data:** 2026-11-07
**Status:** aprovado

**Fontes:** `assistente-docs/README.md`, `00-arquitetura.md`, `01-modulo-auth.md`, `02-modulo-calculadora.md`. Em caso de conflito, este PRD prevalece apenas nos pontos marcados como decisão nova (ver Histórico de Revisões).

---

## 1. Contexto e Problema

O Azimute é um assistente pessoal de **usuário único** com três papéis: contador (finanças), treinador (treino e saúde) e secretária (agenda), com um módulo **Sistema** por cima (missões e consistência). O backend concentra a regra de negócio e o app Android em Kotlin é a interface. A construção está planejada em 16 fases (ver `00-arquitetura.md` → Ordem de construção).

Hoje não existe código. Este PRD cobre **apenas as duas primeiras fases**:

- **Fase 1 — `comum` + `calculadora`.** O kernel de tipos de valor (`Dinheiro`, `Competencia`, `Percentual`) que todos os módulos futuros usam; a base do monólito modular com Spring Modulith e eventos persistidos; e a calculadora de financiamento.
- **Fase 2 — `auth`.** Autenticação por senha uma vez e token por dispositivo, com limite de tentativas.

**Problema concreto que a fase 1 resolve:** ao negociar um financiamento (ex.: moto na concessionária), o usuário recebe valor, entrada, parcela e prazo, mas não a taxa real embutida. Também não consegue comparar propostas de lojas e instituições diferentes depois, a não ser de memória. A taxa precisa ser calculada na hora, sem depender de rede; o backend guarda e compara as propostas salvas.

**Problema que a fase 2 resolve:** o backend será exposto na internet. Sem autenticação desde cedo, todos os controllers precisariam ser revisitados depois, e um serviço público sem proteção é invadido em minutos.

## 2. Objetivo

Ao fim das fases 1 e 2, o backend deve:

1. Ter o kernel `comum` pronto e testado, para ser usado por todos os módulos seguintes sem precisar mudar.
2. Ter a estrutura de módulos com fronteiras **verificadas no build** (um teste quebra se um módulo importar a parte interna de outro) e o registro de eventos persistidos funcionando.
3. Ter o `core` da calculadora em Java validado pelos **vetores de ouro** compartilhados com o app, que serão a referência da implementação em Kotlin.
4. Permitir ao app **salvar, listar, anotar, excluir e comparar** propostas de financiamento, sem duplicar registros quando a mesma requisição é reenviada.
5. Exigir autenticação por token de dispositivo em todos os endpoints de negócio, e permitir revogar um aparelho sem trocar a senha.
6. Rodar localmente via Docker Compose (app + PostgreSQL 18), com fuso `America/Sao_Paulo`.

## 3. Requisitos Funcionais

### 3.1 Fundação (`comum` e infraestrutura)

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF01 | Tipo `Dinheiro`: `BigDecimal`, escala 2, arredondamento `HALF_EVEN`; operações `mais`, `menos`, `negativo`, `ZERO`, criação a partir de texto | Alta |
| RF02 | Tipo `Competencia` (mês/ano): `proxima`, `mais(n)`, intervalo `ate(fim)`; formato `yyyy-MM` na API; persistido como `DATE` com dia 1 | Alta |
| RF03 | Tipo `Percentual`: inteiro de 0 a 100, com `fracao()`; valor fora da faixa é rejeitado | Alta |
| RF04 | Estrutura de pacotes por módulo sob `br.dev.azimute` (`comum`, `auth`, `calculadora/{core,historico}`, `integracao`), verificada pelo Spring Modulith | Alta |
| RF05 | Registro de publicação de eventos do Spring Modulith (persistido em JDBC) configurado e funcionando, mesmo sem eventos de negócio nesta fase | Alta |
| RF06 | Formato único de erro na API: `{ erro, mensagem, detalhes }`, com `erro` em `SCREAMING_SNAKE` e `mensagem` em português | Alta |
| RF07 | Migration inicial com a função `impedir_alteracao()`, que os módulos seguintes usarão nos triggers de imutabilidade | Alta |
| RF08 | Pacotes vazios para os módulos das fases seguintes (`contas`, `fluxo`, `cartoes`, `reserva`, `treino`, `saude`, `sistema`, `agenda`) | Baixa |

### 3.2 Calculadora — `core` (função pura, sem I/O)

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF10 | Tipo `TaxaMensal` (fração, não negativa), com conversão de percentual (`"3.51"` → `0.0351`) e taxa anual equivalente `(1+i)^12 − 1` | Alta |
| RF11 | `montante(pv, i, n)` | Alta |
| RF12 | `parcela(pv, i, n)` pela tabela Price | Alta |
| RF13 | `valorPresente(pmt, i, n)` | Alta |
| RF14 | `taxaImplicita(pv, pmt, n)` por bisseção em `[1e-6; 0,30]`, convergindo com tolerância de 1e-10 | Alta |
| RF15 | `compararAVistaVsFinanciado(precoAVista, entrada, parcela, prazo, rendimento)`, retornando o `Veredito` com o **delta em reais** (positivo = à vista vence), não um booleano. Fórmula na decisão D7 | Média |
| RF16 | `curvaPorEntrada(valorBem, entradaAncora, taxa, prazo, passos)`, retornando pontos com `distanciaDaAncora` | Média |
| RF17 | Arquivo de **vetores de ouro** (`vetores-juros.json`) versionado e consumido pela suíte de testes do `core` | Alta |

### 3.3 Calculadora — `historico` (persistência)

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF20 | `PUT /api/calculadora/historico/{id}` com ID gerado pelo cliente (UUIDv7): cria a simulação; se o mesmo ID for reenviado, devolve o registro existente com status 200 (não 409) | Alta |
| RF28 | Ao salvar, o backend recalcula `valor_financiado`, `taxa_mensal`, `taxa_anual`, `total_pago` e `total_juros` com o `core` Java e rejeita o snapshot divergente com 422 `SNAPSHOT_DIVERGENTE`. O valor gravado é o enviado pelo cliente (o que o usuário viu) | Alta |
| RF21 | `GET /api/calculadora/historico` lista as simulações, opcionalmente filtradas por `bem` | Alta |
| RF22 | `GET /api/calculadora/historico/comparar?bem=...` devolve as propostas do bem, marca a melhor e calcula `economiaEntreMelhorEPior` em reais; considera só `origem = PROPOSTA` por padrão | Alta |
| RF23 | `PATCH /api/calculadora/historico/{id}` altera **somente** `observacao` e `status` (`RECEBIDA`, `DESCARTADA`, `ACEITA`) | Alta |
| RF24 | `DELETE /api/calculadora/historico/{id}` remove a simulação | Média |
| RF25 | Simulação tem `origem` `PROPOSTA` ou `ESTIMATIVA`; toda estimativa aponta para a proposta âncora | Alta |
| RF26 | Os valores calculados são guardados como **snapshot** e nunca recalculados na leitura | Alta |
| RF27 | `calculada_em` (no cliente) e `sincronizada_em` (no servidor) são guardados separadamente | Média |

### 3.4 Auth

| ID | Requisito | Prioridade |
|------|------------------------------------------------|------------|
| RF30 | Usuário inicial criado na subida a partir da variável `APP_SENHA_INICIAL`; se não houver usuário nem variável, a aplicação **não sobe** | Alta |
| RF31 | `POST /api/auth/dispositivos { login, senha, nomeDispositivo }` valida a senha e devolve um token de 256 bits **uma única vez** | Alta |
| RF32 | Todas as requisições de negócio exigem `Authorization: Bearer <token>`; token inválido ou revogado → 401 | Alta |
| RF33 | `GET /api/auth/dispositivos` lista nome, data de criação e último uso | Alta |
| RF34 | `DELETE /api/auth/dispositivos/{id}` revoga o dispositivo, com efeito imediato | Alta |
| RF35 | `POST /api/auth/senha { senhaAtual, senhaNova }` exige a senha atual; ao trocar, revoga todos os outros dispositivos e mantém o atual | Alta |
| RF36 | `ultimo_uso` do dispositivo é atualizado no máximo uma vez por minuto | Baixa |
| RF37 | Tentativas de login (IP, horário, sucesso) são registradas | Alta |

## 4. Requisitos Não Funcionais

| ID | Requisito | Categoria |
|-------|---------------------------------------------------|-----------------|
| RNF01 | Java 25, Spring Boot e Spring Modulith em versões compatíveis com Java 25, confirmadas na documentação oficial antes de fixar no build | Compatibilidade |
| RNF02 | PostgreSQL 18; IDs com `uuidv7()` nativo do banco, exceto em tabelas cujo ID vem do cliente | Compatibilidade |
| RNF03 | Build com Gradle (Kotlin DSL); acesso a dados com Spring Data JDBC (sem JPA); schema só por migrations Flyway (sem `ddl-auto`) | Manutenibilidade |
| RNF04 | Um schema do banco por módulo (`auth`, `calculadora`, …) | Manutenibilidade |
| RNF05 | O fuso `America/Sao_Paulo` é fixado na aplicação, no Jackson e no container | Compatibilidade |
| RNF06 | Virtual threads habilitadas | Performance |
| RNF07 | `taxaImplicita` responde em menos de 10 ms por chamada (≈ 48 iterações de bisseção) | Performance |
| RNF08 | Validar um token custa um hash SHA-256 e uma busca por índice único; o BCrypt só roda no login de um aparelho novo | Performance |
| RNF09 | Testes com JUnit 5, AssertJ e Testcontainers (Postgres real, não banco em memória) | Manutenibilidade |
| RNF10 | Testes de propriedade com valores aleatórios para as invariantes da calculadora (ver RN05) | Manutenibilidade |
| RNF11 | O ambiente local sobe com um único `docker compose up` (app + postgres) | Manutenibilidade |
| RNF12 | Log estruturado em JSON | Manutenibilidade |
| RNF13 | Requisito mínimo de produção: 2 GB de RAM e 20 GB de disco | Disponibilidade |

## 5. Regras de Negócio

**Kernel**
- **RN01:** Dinheiro nunca usa `double`/`float`. No banco é `NUMERIC(19,2)`, nunca `float8` ou `money`.
- **RN02:** Percentual é inteiro; não existe 45,5%.
- **RN03:** Competência é sempre o dia 1 do mês, garantido por `CHECK` no banco; na API, sempre `yyyy-MM`.

**Calculadora**
- **RN04:** `taxaImplicita` lança `SemJurosException` quando `PMT × n ≤ PV`. Valores degenerados (`PV` zero, `PMT` zero, `n ≤ 0`) são rejeitados.
- **RN05:** Propriedade obrigatória: para quaisquer `pv`, `i` e `n` válidos, `taxaImplicita(pv, parcela(pv, i, n), n) ≈ i`, com tolerância de 1e-8.
- **RN06:** Cálculos intermediários usam `MathContext.DECIMAL64`; o arredondamento para 2 casas acontece só no resultado final.
- **RN07:** Vetores de ouro (taxas recalculadas com precisão decimal; tolerância da taxa mensal: 1e-7):

  | Caso | PV | PMT | n | Taxa mensal | Taxa anual | Total pago | Total juros |
  |---|---|---|---|---|---|---|---|
  | moto_36x_proposta_real | 17.430,00 | 860,69 | 36 | 0,0351349 (3,51%) | 51,3% | 30.984,84 | 13.554,84 |
  | moto_24x_proposta_real | 13.990,00 | 930,42 | 24 | 0,0413642 (4,14%) | 62,6% | 22.330,08 | 8.340,08 |
  | celular_10x | 2.400,00 | 279,00 | 10 | 0,0283576 | 39,9% | 2.790,00 | 390,00 |
  | divisao_inexata | 100,00 | 35,00 | 3 | 0,0247975 | 34,2% | 105,00 | 5,00 |
  | prazo_unitario | 1.000,00 | 1.010,00 | 1 | 0,0100000 | 12,7% | 1.010,00 | 10,00 |
  | taxa_muito_baixa | 10.000,00 | 1.005,00 | 10 | 0,0009079 | 1,1% | 10.050,00 | 50,00 |

- **RN08:** O resultado é chamado de **taxa efetiva das parcelas**, nunca de CET.
- **RN09:** Os valores de uma simulação salva (`valor_bem`, `entrada`, `parcela`, `prazo`, `taxa_mensal`, `total_pago`, `total_juros`) são imutáveis, garantido por trigger no banco. Proposta diferente é registro novo.
- **RN10:** `entrada < valor_bem`; `valor_bem > 0`; `parcela > 0`; `prazo > 0`; `entrada ≥ 0`.
- **RN11:** Estimativa sem âncora é rejeitada (constraint no banco).
- **RN12:** A comparação agrupa por `bem` como texto livre, sem cadastro prévio do bem.
- **RN13:** A calculadora não publica eventos e não conversa com outros módulos.
- **RN14:** Não existe endpoint de cálculo: o cálculo acontece no cliente.

**Auth**
- **RN15:** Senha com no mínimo 12 caracteres, sem exigir símbolos ou maiúsculas.
- **RN16:** Limite por IP: 5 falhas em 15 minutos bloqueiam aquele IP por 15 minutos.
- **RN17:** Limite global: 20 falhas em 1 hora, de qualquer IP, bloqueiam o cadastro de aparelhos novos por 1 hora. Os tokens já emitidos continuam funcionando.
- **RN18:** Usuário inexistente e senha errada recebem a mesma resposta, no mesmo tempo.
- **RN19:** O login do usuário inicial é `admin`.

## 6. Requisitos de Segurança

- **SEC01:** A senha é armazenada com BCrypt, custo 12.
- **SEC02:** O token nunca é armazenado em texto; o banco guarda só o SHA-256, com índice único.
- **SEC03:** A API é stateless: sem `JSESSIONID`, sem HTTP Basic, sem login por formulário. CSRF desabilitado só porque não há cookie de sessão.
- **SEC04:** Sem senha padrão: a subida falha se não houver usuário nem `APP_SENHA_INICIAL`.
- **SEC05:** Segredos só em variáveis de ambiente, nunca em `application.properties` versionado.
- **SEC06:** Em produção, a resposta de erro não traz stack trace; o detalhe vai para o log.
- **SEC07:** Nunca registrar no log senha, token ou corpo de requisição de login.
- **SEC08:** Tentativas de login com falha são registradas com o IP.
- **SEC09:** Actuator: só `/actuator/health` é público, sem detalhes; o resto exige autenticação.
- **SEC10:** O Postgres nunca é exposto fora da rede interna do Docker.
- **SEC11:** Cabeçalhos `X-Content-Type-Options` e HSTS.
- **SEC12:** HTTPS obrigatório no perfil `prod` (`requiresSecure()` com `forward-headers-strategy=framework` atrás do proxy). No perfil `dev` (local, sem proxy) HTTP puro é aceito. A exigência fica ligada por padrão e só o perfil `dev` a desliga; um teste garante que, no perfil `prod`, HTTP puro é rejeitado e `X-Forwarded-Proto: https` é aceito (decisão D1).

## 7. Casos de Uso

### Fluxo Principal A — Salvar uma proposta (calculadora)
1. No app, o usuário digita valor do bem, entrada, parcela e prazo; o app calcula a taxa localmente.
2. O usuário salva. O app gera um UUIDv7 e envia `PUT /api/calculadora/historico/{id}` com dados e resultados.
3. O backend grava o snapshot e responde 200 com o registro.
4. Estado final: a proposta aparece no histórico e na comparação do bem.

### Fluxo Principal B — Comparar propostas
1. O app chama `GET /api/calculadora/historico/comparar?bem=Honda CB 300F`.
2. O backend devolve as propostas (só `PROPOSTA`), marca a melhor e calcula a economia em reais entre a melhor e a pior.

### Fluxo Principal C — Cadastrar um aparelho
1. O app envia login, senha e nome do aparelho para `POST /api/auth/dispositivos`.
2. O backend confere o limite de tentativas, valida a senha, gera o token, grava o hash e devolve o token uma única vez.
3. Daí em diante, o app manda `Bearer <token>` em toda requisição.

### Fluxos Alternativos
- **Alt A — Reenvio pela fila offline:** o mesmo `PUT /{id}` chega de novo (a resposta anterior se perdeu). O backend devolve o registro existente, com 200, e não cria nada novo.
- **Alt B — Tentativa de alterar valores:** um `PATCH` com campos de valor é rejeitado; mesmo um `UPDATE` direto no banco é barrado pelo trigger.
- **Alt C — Aparelho perdido:** de outro aparelho, o usuário revoga o perdido via `DELETE /api/auth/dispositivos/{id}`; o token dele passa a receber 401 imediatamente.
- **Alt D — Ataque de força bruta:** após 5 falhas de um IP, o login fica bloqueado para ele; após 20 falhas globais, ninguém cadastra aparelho novo por 1 hora, mas os aparelhos já cadastrados continuam funcionando.
- **Alt E — Troca de senha:** com a senha atual correta, a senha muda e todos os outros dispositivos são revogados.

### Casos de Borda
- `PMT × n == PV` → `SemJurosException`, traduzida para erro de domínio na API.
- `n = 1`; taxa muito baixa (próxima de 1e-6); taxa acima de 30% ao mês (fora do intervalo da bisseção) → `TaxaForaDaFaixaException`, traduzida para o erro `TAXA_FORA_DA_FAIXA` (422) (decisão D5).
- Bisseção que não converge em 200 iterações → `NaoConvergiuException`.
- Mesmo ID reenviado com **valores diferentes** → 409 `SIMULACAO_DIVERGENTE`. Diferença só em campos anotativos (`observacao`, `status`) não conta: devolve o existente com 200 (decisão D2).
- `DELETE` de uma proposta que é âncora de estimativas → 409 `SIMULACAO_E_ANCORA`, com os IDs das estimativas em `detalhes`. É preciso excluir as estimativas antes (decisão D3).
- Comparação de um bem com uma única proposta, ou com nenhuma.
- Comparação entre propostas com prazos ou valores financiados diferentes: a melhor é a de **menor taxa mensal** (única medida comparável entre prazos), com desempate pela data mais recente. `economiaEntreMelhorEPior` = `total_juros` da pior − `total_juros` da melhor, como no exemplo da doc; a resposta inclui `prazo` e `valorFinanciado` de cada proposta para deixar visível quando eles diferem (decisão D4). Com uma proposta só, ela é a melhor e a economia é zero; sem propostas, a lista vem vazia e a economia é zero.
- Senha nova com menos de 12 caracteres → erro de validação.
- Subida sem usuário e sem `APP_SENHA_INICIAL` → a aplicação não sobe.

## 8. Fora do Escopo

- **App Android/Kotlin**, incluindo o `core` espelhado em Kotlin, a fila offline (`sync`) e as notificações locais. Este PRD entrega apenas o contrato do backend e o arquivo de vetores de ouro.
- Endpoint de cálculo no backend (decisão da doc 02).
- `dataDeEquilibrio` (operação de antecipação, ainda não especificada) e SAC.
- IOF, seguro e tarifas tratados separadamente.
- Tipo `Semana` (entra na fase 9).
- Trigger de auditoria e schema `auditoria` (não há tabelas de movimentação financeira nestas fases; entram com `contas` na fase 3).
- Credenciais de integrações (refresh token do Google criptografado, `state` do OAuth, callback do Google): ficam para a fase 13. A rota pública do callback **não** é aberta nesta fase.
- Cadastro de usuários, recuperação de senha por e-mail, papéis, 2FA e JWT.
- **Implantação pública:** VPS, domínio, Caddy/HTTPS, firewall, backup diário fora da VPS e atualizações automáticas. VPS e domínio ainda estão em avaliação; até lá, o backend roda só localmente. Entra num PRD próprio de implantação, antes de qualquer exposição na internet.
- Módulos das fases 3 a 16.
- Prometheus, Grafana e tracing.

## 9. Dependências e Riscos

| Tipo | Descrição | Impacto |
|--------------|--------------------------------------------------------|----------|
| Dependência | Versão do Spring Boot e do Spring Modulith compatível com Java 25 precisa ser confirmada na documentação oficial | Alto |
| Dependência | PostgreSQL 18 com `uuidv7()` nativo; se não houver, gerar o ID na aplicação | Médio |
| Dependência | Docker disponível no ambiente de desenvolvimento (WSL2) para Compose e Testcontainers | Alto |
| Risco | O `core` em Java e o futuro `core` em Kotlin divergirem (`MathContext`, tolerância, arredondamento). Mitigação: vetores de ouro como fonte de verdade; Kotlin Multiplatform como plano B | Alto |
| Risco | Os vetores da doc 02 trazem a taxa arredondada (`0.03510`, `0.04140`) com tolerância de 1e-5, mas as taxas reais são 0,0351349 e 0,0413642: o teste falharia com a implementação correta. Os valores corrigidos estão em RN07 (decisão D8) | Médio |
| Risco | Snapshot enviado pelo cliente incoerente com os valores informados. Mitigação: o backend recalcula com o `core` Java e rejeita divergências (decisão D6) | Médio |
| Risco | Sem proxy HTTPS, o `requiresSecure()` rejeita tudo localmente, ou acaba sendo desligado "para funcionar" e esquecido em produção (SEC12) | Alto |
| Risco | Atrás de proxy, o IP do limite de tentativas precisa vir do `X-Forwarded-For` confiável; ler o IP errado anula o limite por IP | Médio |
| Risco | `APP_SENHA_INICIAL` esquecida no ambiente depois do primeiro boot | Baixo |
| Observação | O pacote raiz passa a ser `br.dev.azimute` em vez de `br.dev.assistente` (diverge da decisão 24 da doc 00) | Baixo |

## 10. Critérios de Aceitação

**Fundação**
- [x] `Dinheiro`, `Competencia` e `Percentual` cobertos por testes, incluindo `HALF_EVEN` e rejeição de valores inválidos (RF01–RF03, RN01–RN03)
- [x] O teste de verificação do Spring Modulith passa e quebra se um módulo importar a parte interna de outro (RF04)
- [x] A tabela de publicação de eventos é criada e o registro de eventos funciona num teste de integração (RF05)
- [x] Todo erro da API segue `{ erro, mensagem, detalhes }`, sem stack trace no perfil de produção (RF06, SEC06)
- [x] A migration V001 cria `impedir_alteracao()` (RF07)
- [x] O ambiente local sobe com `docker compose up`, com o fuso `America/Sao_Paulo` (RNF05, RNF11)

**Calculadora**
- [x] `taxaImplicita` reproduz os vetores de ouro reais (3,51% / 51,3%; 4,14% / 62,6%; 2,84%) (RN07)
- [x] A propriedade de ida e volta passa para milhares de combinações aleatórias (RN05)
- [x] Os casos degenerados lançam as exceções previstas (RN04)
- [x] `compararAVistaVsFinanciado` devolve o delta em reais (RF15)
- [x] Salvar duas vezes com o mesmo ID não duplica e devolve 200 (RF20)
- [x] Alterar valores de uma simulação salva falha no banco; `observacao` e `status` podem mudar (RF23, RN09)
- [x] Estimativa sem âncora é rejeitada (RN11)
- [x] A comparação ignora estimativas por padrão e devolve a economia em reais (RF22)

**Auth**
- [x] Requisição sem token, com token inválido ou revogado → 401; token válido passa (RF32, RF34)
- [x] O token não aparece em texto no banco (SEC02)
- [x] Login com senha errada não revela se o usuário existe (RN18)
- [x] 5 falhas bloqueiam o IP; o bloqueio global não derruba tokens existentes (RN16, RN17)
- [x] Troca de senha sem a senha atual falha; com sucesso, revoga os outros dispositivos (RF35)
- [x] A aplicação não sobe sem usuário nem `APP_SENHA_INICIAL` (RF30, SEC04)
- [x] Comportamento HTTP/HTTPS validado conforme a decisão de SEC12

**Pendências resolvidas antes da aprovação**
- [x] Todos os itens pendentes deste documento têm decisão registrada (seção 11)

## 11. Decisões sobre as pendências

Tomadas na aprovação (v1.1), por recomendação, com aval do usuário para seguir.

| # | Pendência | Decisão | Motivo |
|---|---|---|---|
| D1 | HTTPS sem proxy no ambiente local | `requiresSecure()` ligado por padrão; desligado só no perfil `dev`; teste no perfil `prod` | Desligar "para funcionar" e esquecer é o risco real; o padrão seguro protege a produção |
| D2 | Mesmo ID com corpo diferente | Valores iguais → 200 com o existente; valores diferentes → 409 `SIMULACAO_DIVERGENTE` | Reenvio idêntico é rede ruim; reenvio diferente é bug do cliente e não deve ser escondido |
| D3 | Excluir proposta que é âncora | Bloquear com 409 `SIMULACAO_E_ANCORA` | Cascata apaga dado de negociação sem aviso; desvincular viola `estimativa_exige_ancora` |
| D4 | Critério de "melhor proposta" | Menor taxa mensal; economia = diferença de `total_juros` (como na doc); prazo e financiado expostos | A taxa é comparável entre prazos; o valor em reais é o argumento de negociação |
| D5 | Taxa acima de 30% a.m. | `TaxaForaDaFaixaException` → 422 `TAXA_FORA_DA_FAIXA` | Resultado errado é pior que erro explícito |
| D6 | Backend confere o snapshot? | Sim: recalcula e rejeita divergência. `PROPOSTA`: recalcula a taxa a partir da parcela (tolerância 1e-6). `ESTIMATIVA`: recalcula a parcela a partir da taxa da âncora (exata em centavos) e exige `taxa_mensal` igual à da âncora. Em ambas, `valor_financiado = valor_bem − entrada`, `total_pago = parcela × prazo` e `total_juros = total_pago − valor_financiado`, exatos | Dá ao `core` Java uma função real no backend e impede histórico incoerente |
| D7 | Fórmula do `Veredito` | `custoTotalFinanciado = entrada + parcela × prazo`; `custoTotalAVista = precoAVista`; `rendimentoDoDinheiroGuardado = parcela × prazo − valorPresente(parcela, rendimento, prazo)`; `delta = custoTotalFinanciado − rendimentoDoDinheiroGuardado − custoTotalAVista` (equivale a `entrada + VP das parcelas − precoAVista`); `taxaFinanciamento = taxaImplicita(precoAVista − entrada, parcela, prazo)`, ou zero quando não há juros | Traz as parcelas a valor presente pela taxa do rendimento: é exatamente "financiar e deixar o dinheiro rendendo" |
| D8 | Vetores sem valor esperado; taxas arredondadas na doc | Valores recalculados com precisão decimal (RN07), tolerância 1e-7 | Os vetores da doc 02 falhariam com a implementação correta |

---

## Histórico de Revisões

| Versão | Data | Alteração |
|--------|------------|----------------------------|
| 1.0 | 2026-11-07 | Criação inicial. Escopo: fases 1–2. Decisões novas: pacote raiz `br.dev.azimute`; implantação pública adiada (sem VPS/domínio) |
| 1.1 | 2026-11-07 | Pendências resolvidas (D1–D8); RF28 (conferência do snapshot); RN07 com vetores corrigidos; status aprovado |
