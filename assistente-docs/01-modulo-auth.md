# 01 — Módulo `auth`

## Escopo

Autenticação de usuário único para uma aplicação **exposta na internet**. Mais a guarda das credenciais que o sistema usa para falar com serviços externos (Google, Claude).

Sem cadastro, sem recuperação de senha por e-mail, sem perfis, sem papéis, sem OAuth para login. Cada um desses custa código, teste e superfície de ataque, e nenhum protege nada a mais para um usuário só.

> **O que mudou desde a primeira versão.** O módulo foi desenhado para uma aplicação privada, com HTTP Basic em toda requisição. Com o backend numa VPS pública, isso deixa de ser suficiente. O próprio documento original previa: "se um dia isso for exposto, migre — o `SecurityFilterChain` é o único ponto que muda". É essa migração.

## Decisão

**Senha uma vez, token opaco por dispositivo depois. HTTPS obrigatório.**

1. No primeiro acesso de um aparelho, o app manda login e senha para `POST /api/auth/dispositivos`.
2. O servidor valida a senha (BCrypt), gera um token aleatório de 256 bits e devolve **uma vez só**.
3. O banco guarda só o **hash** do token (SHA-256), nunca o token.
4. O app guarda o token no armazenamento seguro do Android (Keystore) e manda `Authorization: Bearer <token>` em toda requisição.
5. A senha não fica salva no celular.

### Por que não continuar com HTTP Basic

Sobre TLS, Basic é equivalente a mandar um token — a crítica "Basic é inseguro" continua errada. O problema é outro, e aparece quando o servidor é público:

| | HTTP Basic | Token por dispositivo |
|---|---|---|
| O que o celular guarda | A senha | Um token que só serve para esta API |
| Celular perdido | Trocar a senha em todo lugar | Revogar aquele aparelho |
| Custo por requisição | BCrypt (~250 ms) ou cache | Um hash SHA-256 e uma busca por índice |
| Saber de onde veio o acesso | Não | Cada token tem nome e último uso |

### Por que não JWT

JWT resolve dois problemas: validar sem consultar o banco (escala) e delegar autenticação entre serviços. Você não tem nenhum dos dois. O que sobra é: expiração, refresh, revogação difícil, rotação de segredo e uma classe inteira de bugs de relógio — para proteger um usuário. Token opaco no banco é revogável na hora, com um `UPDATE`.

---

## Modelo

```java
public record Usuario(
    UsuarioId id,
    String login,
    String senhaHash,
    Instant criadoEm,
    Instant ultimoAcesso
) {}

public record Dispositivo(
    DispositivoId id,
    String nome,              // "S25 Ultra", "Notebook"
    String tokenHash,         // SHA-256 do token; o token nunca é guardado
    Instant criadoEm,
    Instant ultimoUso,
    Instant revogadoEm        // null = ativo
) {}
```

```sql
CREATE SCHEMA auth;

CREATE TABLE auth.usuarios (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    login          TEXT NOT NULL UNIQUE,
    senha_hash     TEXT NOT NULL,
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_acesso  TIMESTAMPTZ
);

CREATE TABLE auth.dispositivos (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    nome          TEXT NOT NULL,
    token_hash    TEXT NOT NULL UNIQUE,
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_uso    TIMESTAMPTZ,
    revogado_em   TIMESTAMPTZ
);

CREATE TABLE auth.tentativas_login (
    id      BIGSERIAL PRIMARY KEY,
    ip      INET NOT NULL,
    em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sucesso BOOLEAN NOT NULL
);

CREATE INDEX ON auth.tentativas_login (ip, em DESC);
```

Tabela, não `application.properties`. Senha em arquivo de configuração acaba no git — sempre.

**Por que SHA-256 e não BCrypt para o token:** o token tem 256 bits aleatórios, então não há o que adivinhar por força bruta, e BCrypt existe para proteger segredos fracos (senhas). SHA-256 permite buscar o token por índice único, em microssegundos.

---

## Configuração

```java
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenFilter tokenFilter) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/dispositivos").permitAll()
                .requestMatchers("/api/integracao/google/callback").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .requiresChannel(c -> c.anyRequest().requiresSecure())
            .headers(h -> h
                .contentTypeOptions(Customizer.withDefaults())
                .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000)))
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

O `TokenFilter` lê o header `Bearer`, calcula o SHA-256, busca em `auth.dispositivos` com `revogado_em IS NULL` e atualiza `ultimo_uso` (no máximo uma vez por minuto, para não escrever no banco a cada requisição).

Escolhas que merecem explicação:

**`csrf().disable()`** é seguro **apenas** porque a API é stateless e não usa cookie de sessão. CSRF depende do navegador anexar credencial automaticamente; com token no header e sem cookie, não há o que forjar. Se um dia você adicionar sessão por cookie, o CSRF volta a ser necessário.

**`STATELESS`** impede o Spring de criar `JSESSIONID`. Sem isso ele cria sessão mesmo sem login por formulário, e você acaba com estado em memória que se perde no restart.

**`requiresSecure()`** rejeita HTTP puro. Atrás do Caddy, a aplicação recebe HTTP na rede interna; ela só sabe que a conexão original era HTTPS pelo `X-Forwarded-Proto`. Por isso `server.forward-headers-strategy=framework` é obrigatório — sem ele, toda requisição é rejeitada ou, pior, a regra é desligada "para funcionar".

**Custo 12 no BCrypt** leva uns 250 ms para verificar. É lento de propósito — é a defesa contra força bruta. Agora ele roda só no login de um aparelho novo, não a cada requisição, então o cache de autenticação da primeira versão deixou de ser necessário.

**`/api/integracao/google/callback` público** porque é o Google que chama esse endereço, sem o seu token. Ele é protegido pelo parâmetro `state` (ver Credenciais de integrações).

---

## Limite de tentativas

Com o backend público, alguém vai tentar senhas. O limite:

- **Por IP:** 5 falhas em 15 minutos bloqueiam aquele IP por 15 minutos.
- **Global:** 20 falhas em 1 hora, de qualquer IP, bloqueiam o login de aparelhos novos por 1 hora. Os tokens já emitidos continuam funcionando — você não fica trancado fora.
- **Resposta igual** para usuário inexistente e senha errada, e com o mesmo tempo de resposta, para não revelar o login.

Implementação simples com a tabela `auth.tentativas_login`. Se quiser, o Caddy também pode limitar requisições por IP na borda; as duas camadas não conflitam.

---

## Primeiro acesso

O usuário inicial é criado na subida, com senha vinda de variável de ambiente:

```java
@Component
class SeedUsuario implements ApplicationRunner {

    public void run(ApplicationArguments args) {
        if (repo.existeAlgum()) return;

        String senha = System.getenv("APP_SENHA_INICIAL");
        if (senha == null || senha.isBlank())
            throw new IllegalStateException(
                "APP_SENHA_INICIAL não definida e nenhum usuário existe");

        repo.criar(new Usuario(null, "admin", encoder.encode(senha), now(), null));
        log.warn("Usuário inicial criado. Troque a senha no primeiro acesso.");
    }
}
```

Falhar na subida quando não há usuário nem variável é intencional. A alternativa — subir com senha padrão — é como vazamento acontece, e num servidor público isso dura minutos.

Depois de criado o usuário, remova `APP_SENHA_INICIAL` do ambiente.

---

## Endpoints

```
POST   /api/auth/dispositivos          { login, senha, nomeDispositivo } → { token } (uma vez)
GET    /api/auth/dispositivos          lista: nome, criado, último uso
DELETE /api/auth/dispositivos/{id}     revoga
POST   /api/auth/senha                 { senhaAtual, senhaNova }
```

**Troca de senha** exige a senha atual mesmo já estando autenticado. Custa uma linha e protege contra aparelho esquecido desbloqueado. Ao trocar, **todos os outros dispositivos são revogados** — o aparelho que fez a troca continua ativo.

Validação mínima: 12 caracteres. Sem exigência de símbolo ou maiúscula — regra de composição empurra para senha ruim e memorável; comprimento é o que importa. Com o servidor público, use um gerenciador de senhas e 20+ caracteres.

---

## Credenciais de integrações

O login não usa OAuth. Mas o sistema é **cliente** de dois serviços externos, e as credenciais deles moram aqui:

| Segredo | Onde | Por quê |
|---|---|---|
| Chave da API do Claude | Variável de ambiente | Nunca no banco nem no repositório |
| Client ID e secret do Google | Variável de ambiente | Idem |
| Refresh token do Google | `integracao.credenciais`, criptografado (AES-GCM) | Precisa sobreviver a reinícios |
| Chave de criptografia | Variável de ambiente | Banco vazado não expõe o token |

**Autorização do Google (fluxo de servidor):**

1. No app, você toca em "Conectar Google Agenda". O app chama `POST /api/integracao/google/autorizar` (autenticado) e recebe uma URL do Google com um `state` aleatório, de uso único, válido por 10 minutos.
2. O app abre essa URL no navegador. Você autoriza.
3. O Google redireciona para `https://<seu-dominio>/api/integracao/google/callback?code=...&state=...`.
4. O backend confere o `state`, troca o `code` pelo refresh token e guarda criptografado.

O `state` é o que impede alguém de ligar a conta Google dele ao seu sistema. Escopos mínimos: ler horários ocupados (freeBusy) e criar e editar eventos. Confirmar os nomes exatos dos escopos na documentação do Google na fase 13.

---

## O que fica fora do escopo

| Não fazer | Por quê |
|---|---|
| Cadastro de usuário | Um usuário, criado por seed |
| Recuperação por e-mail | Exige SMTP, token, expiração — para você mesmo. Perdeu a senha: redefina pelo banco, com acesso SSH |
| Papéis e permissões | Não há segundo papel |
| 2FA | Evolução possível (TOTP) se você quiser; com senha longa e limite de tentativas, o risco real já é baixo |
| JWT | Ver acima |

Se algum desses passar a fazer sentido, é sinal de que o projeto mudou de natureza — e aí vale revisitar o módulo inteiro, não remendar.

---

## Testes

```java
@Test void requisicao_sem_token_retorna_401()
@Test void token_invalido_retorna_401()
@Test void token_revogado_retorna_401()
@Test void token_valido_passa()
@Test void token_nao_e_guardado_em_texto_no_banco()
@Test void login_com_senha_errada_nao_revela_se_usuario_existe()
@Test void cinco_falhas_bloqueiam_o_ip()
@Test void bloqueio_global_nao_derruba_tokens_existentes()
@Test void http_puro_e_rejeitado()
@Test void https_via_proxy_e_aceito_com_forwarded_proto()
@Test void troca_de_senha_sem_senha_atual_falha()
@Test void troca_de_senha_revoga_os_outros_dispositivos()
@Test void callback_do_google_com_state_invalido_e_rejeitado()
@Test void aplicacao_nao_sobe_sem_usuario_nem_variavel()
```

O último continua o mais importante e o mais esquecido.
