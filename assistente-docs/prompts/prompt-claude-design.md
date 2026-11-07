# Prompt para o Claude Design — Painel de reserva, projeção e calculadora

> Cole o bloco abaixo inteiro no Claude Design. Ele gera o front-end de três telas. O app final é Kotlin no Android; o protótipo gerado serve como referência visual, não como código.

---

## PROMPT

Preciso do front-end de um app pessoal de gestão de reserva financeira. Não é um app comercial, não tem onboarding, não tem tela de login, não tem landing page. É uma ferramenta de uso diário de uma pessoa só, que já sabe o que está fazendo.

### Contexto de quem usa

Um desenvolvedor que decidiu não financiar uma moto depois de calcular que a taxa real era 4,14% ao mês. Ele guarda dinheiro em um único lugar, mas divide mentalmente em cinco categorias por percentual. Ele muda de ideia sobre os percentuais com frequência — já reorganizou o plano sete vezes em uma tarde. A interface precisa tratar "mudar o plano" como ação rotineira e barata, não como reconfiguração.

Ele não quer ser motivado. Não quer confete, badge, streak, mensagem de parabéns nem emoji de foguete. Quer ver o número e decidir.

### Telas

**1. Painel (tela principal)**

O elemento central é a **distribuição do aporte**. Ele digita um valor no topo — ex.: R$ 1.200 — e vê imediatamente, antes de confirmar, como esse valor se parte entre as categorias, com o saldo resultante de cada uma. Só depois clica em "Registrar aporte". A pré-visualização é o coração da tela: digitar e ver o efeito acontecendo é a interação principal do produto.

Abaixo, as cinco categorias. Cada uma mostra: nome, percentual atual, saldo, teto (quando existe) e progresso até o teto. Categorias com teto definido precisam comunicar quanto falta. Quando uma atinge o teto, ela muda de estado visual e o percentual dela se redistribui entre as outras — esse momento merece tratamento, é a regra mais importante do sistema.

As categorias são: Moto, Emergência (teto R$ 12.000), Moradia, Viagem e Celular (que já foi concluída e está inativa — categorias encerradas continuam visíveis, mas apagadas).

**2. Projeção**

Um gráfico de linha com duas séries sobrepostas: **planejado** e **real**. O planejado é a curva que o sistema projetou quando o plano foi criado. O real é o que efetivamente aconteceu. A distância entre as duas é a informação que importa — não deixe ela sutil.

O eixo do tempo vai de setembro de 2026 a dezembro de 2028. Marque no gráfico os pontos onde cada meta é atingida.

Precisa ter um seletor para ver a projeção por categoria ou consolidada, e controles para simular: e se o aporte subir R$ 100/mês? E se eu parar de aportar por três meses? A simulação aparece como uma terceira linha, tracejada, sem alterar o plano salvo. Ele vai usar isso muito — simular sem se comprometer é como ele pensa.

**3. Calculadora de financiamento (rota separada, autônoma)**

Uma calculadora que ele vai abrir no celular dentro da concessionária, enquanto negocia. Precisa ser rápida e legível em pé, segurando o telefone com uma mão.

Entrada: valor do bem, entrada, número de parcelas, valor da parcela.
Saída, em ordem de destaque: **taxa mensal**, taxa anual, total pago, total de juros.

Abaixo, uma comparação: quanto esse mesmo dinheiro renderia se ele guardasse em vez de financiar. Um veredito curto e direto — sem eufemismo, sem "considere avaliar".

Essa tela é a única do app que pode ser incisiva. Uma taxa de 4% ao mês deve parecer alarmante na tela.

### Direção visual

Densidade de informação alta. Ele lê tabela sem dificuldade e prefere ver tudo de uma vez a navegar entre abas. Não infantilize e não esconda número atrás de card bonito.

Números são o conteúdo principal — trate tipografia tabular como decisão de projeto, não detalhe. Valores em reais precisam alinhar na vertical quando empilhados.

Evite o visual padrão de fintech: gradiente roxo, ilustração de pessoa segurando moeda, card com sombra difusa, cor de destaque terracota. Ele já rejeitou proposta que parecia genérica. Encontre uma direção que venha do assunto — dinheiro sendo dividido, tempo passando, metas se aproximando — e não do catálogo de app financeiro.

Escuro e claro, ambos. Ele usa de dia e de noite.

### Detalhes que importam

- Valores em pt-BR: R$ 1.234,56. Nunca 1,234.56.
- Percentuais precisam somar 100%. Se não somarem, a interface avisa antes de deixar salvar.
- Editar percentual é ação de dois cliques, não fluxo de configuração.
- Arredondamento: ao dividir R$ 1.000 por percentuais, sobram centavos. Mostre para onde eles foram.
- Estado vazio: no primeiro acesso não há histórico. Convide a registrar o primeiro aporte, não explique o produto.

### Técnico

React + Tailwind para o protótipo. Consome uma API REST (backend Java + Spring Boot). Monte com dados de exemplo realistas — saldos na casa dos milhares, histórico de uns doze meses, uma categoria já concluída — mas isole as chamadas de API numa camada só, para eu trocar mock por endpoint real depois.
