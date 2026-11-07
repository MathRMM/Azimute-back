# Prompt para o Claude Design — Seção Fluxo (ganhos, gastos e cartões)

> Módulo separado do app de reserva. Cole o bloco abaixo inteiro no Claude Design. O app final é Kotlin no Android; o protótipo gerado serve como referência visual.
>
> Depois que este prompt foi escrito, o módulo `contas` entrou no desenho: todo gasto à vista sai de uma conta, e todo cartão tem conta de pagamento. Se for gerar de novo, peça também a escolha da conta no registro do gasto.

---

## PROMPT

Preciso do front-end de uma seção de controle de ganhos e gastos, com foco pesado em cartão de crédito. É um módulo independente — não reaproveite layout, navegação ou componentes de nenhuma tela anterior. Ele vai conviver com um app de reserva financeira que já existe, mas é uma superfície própria.

É ferramenta pessoal de uso diário, de uma pessoa só, que trabalha com software e lê tabela sem dificuldade. Sem onboarding, sem login, sem landing.

### A pergunta que a tela responde

Uma pessoa que recebe salário no dia 5 quer saber, em qualquer momento do mês: **quanto do meu dinheiro já está comprometido, e até quando.** Não é sobre o mês corrente — é sobre os próximos doze, porque parcelamento no cartão compromete o futuro e é fácil perder a conta.

Tudo na tela serve a essa pergunta.

---

### Bloco 1 — Ganhos

Cada entrada de ganho registra dois valores: **bruto** e **líquido**. O bruto é referência, o líquido é o que entra na conta e o que alimenta todo cálculo do app.

A partir dos dois, mostre a **taxa de desconto** — quanto some entre bruto e líquido, em percentual. Essa métrica é o motivo do campo duplo existir: conhecendo o histórico de desconto, dá para prever o líquido de um ganho futuro antes de recebê-lo. Mostre a taxa da entrada atual e a média histórica lado a lado.

Ganhos podem ser recorrentes (salário) ou pontuais (PLR, freela). Recorrentes entram automaticamente nos meses seguintes; pontuais ficam só onde foram lançados.

Um campo de projeção: digitar um bruto futuro e ver o líquido estimado aplicando a taxa média. Simples, mas é o que transforma o registro em ferramenta.

---

### Bloco 2 — Gastos

Registro de item gasto. Cada um tem: descrição, valor, data, categoria e **forma de pagamento**.

A forma de pagamento é a bifurcação mais importante da tela:

- **Débito / à vista** — sai da conta agora, afeta só o mês corrente.
- **Crédito** — vai para um cartão específico, com número de parcelas, e afeta N meses futuros.

Essas duas escolhas produzem consequências completamente diferentes. A interface precisa deixar isso visível no momento do registro: ao escolher crédito e informar as parcelas, mostre imediatamente em quais meses aquele gasto vai aparecer e de quanto ele deixa a fatura em cada um. A pessoa decide melhor quando vê o efeito antes de confirmar.

**Categorias.** Comece com um conjunto pré-definido — Alimentação, Moradia, Transporte, Saúde, Educação, Lazer, Assinaturas, Vestuário, Outros — e permita criar novas a qualquer momento, direto no fluxo de registro, sem ir a uma tela de configuração. Categoria criada na hora é usada na hora.

**Gastos recorrentes.** Assinaturas e mensalidades são o que mais escapa: valores pequenos, cobrança automática, ninguém lembra. Elas precisam de tratamento próprio — cadastro com dia de cobrança e periodicidade (mensal, anual), e aparição automática nos meses corretos.

O caso da assinatura anual merece cuidado: uma anuidade de R$ 240 cobrada em março **não** é R$ 20 por mês. Ela é R$ 240 em março e zero nos outros onze. Mostre-a nos R$ 240, no mês certo. O objetivo é previsibilidade real, não média contábil. Mas ofereça o custo mensal equivalente como informação secundária, porque serve para comparar assinaturas entre si.

---

### Bloco 3 — Cartões de crédito

Este é o núcleo da seção e merece o maior peso visual. Vários cartões, cada um com identidade própria e comportamento próprio.

**Cada cartão tem:**
- Limite total
- Limite comprometido e limite disponível
- Dia de fechamento
- Dia de vencimento
- Fatura atual
- Parcelas futuras já comprometidas, mês a mês

**Regra do dia útil.** O vencimento pode ser deslocado quando cai em fim de semana ou feriado. A interface mostra a **data nominal como prazo de pagamento** e, de forma discreta e secundária, até quando dá para pagar sem encargo se houve deslocamento. Nunca apresente a data adiada como "o vencimento": pagar na nominal nunca custa nada; confiar na adiada pode custar juros de rotativo.

**Regra do fechamento.** Compra feita depois do fechamento não entra na fatura que vai vencer — entra na seguinte. Essa é a regra que mais confunde na prática e a interface precisa comunicá-la sem que a pessoa tenha que raciocinar: ao registrar uma compra no crédito, diga em qual fatura ela vai cair e quando essa fatura vence. Se estiver perto do fechamento, isso é a informação mais útil da tela.

Mostre também quantos dias faltam para o próximo fechamento de cada cartão. Cartão prestes a fechar muda a decisão de comprar hoje ou amanhã.

**Composição da fatura.** Ao abrir um cartão, é preciso ver a fatura decomposta em: compras à vista do período, parcelas de compras anteriores (com indicação de qual parcela de qual total — "3 de 12"), e assinaturas que caem naquele mês. Cada linha rastreável até o item original.

---

### O elemento central: horizonte de comprometimento

O que amarra a seção inteira é uma **visão dos próximos doze meses** mostrando, para cada mês futuro, quanto já está comprometido — somando parcelas de todos os cartões e assinaturas recorrentes.

É essa visão que responde "até que mês eu tenho gasto". Ela precisa deixar evidente:
- O mês em que o comprometimento cai de forma relevante, quando um parcelamento grande termina
- O mês em que o comprometimento zera, se zerar
- Os picos — meses onde várias parcelas e anuidades coincidem

Trate isso como o elemento que a pessoa vai lembrar da tela. Não é um gráfico de barras genérico enfiado num card: é o mapa do futuro financeiro dela, e a coisa que ela vai abrir o app para consultar.

Contra esse comprometimento, mostre a renda líquida prevista do mesmo mês. A distância entre as duas é a sobra — e a sobra é o que vai virar aporte de reserva depois.

---

### Direção visual

Densidade alta. A pessoa prefere ver tudo de uma vez a navegar entre abas. Não esconda número atrás de card decorativo e não infantilize.

Números são o conteúdo — tipografia tabular é decisão de projeto, não detalhe. Valores em reais alinham na vertical quando empilhados.

Cada cartão de crédito precisa ser distinguível de relance, porque a pessoa vai ter vários e alterna entre eles rápido. Resolva isso sem imitar plástico de cartão real — a metáfora do retângulo com chip e bandeira é o clichê mais gasto da categoria.

**Evite o visual padrão de fintech:** gradiente roxo, ilustração de pessoa com moeda, card com sombra difusa, acento terracota, ícone de carteira. Encontre a direção no assunto — tempo comprometido, dinheiro que já tem dono, limite que se esgota — e não no catálogo de app financeiro.

Escuro e claro, ambos.

### Tom da interface

Sem julgamento. A tela não parabeniza por economizar nem alerta por gastar. Ela informa e sai do caminho. Nada de "você está indo bem!", nada de emoji, nada de meta de gamificação.

A única exceção é limite: passar de 80% do limite de um cartão é fato operacional relevante e merece sinalização. Mas sinalização, não sermão.

### Detalhes que importam

- Valores em pt-BR: R$ 1.234,56. Nunca 1,234.56.
- Registrar gasto é a ação mais frequente do app. Deve custar poucos toques e ficar sempre alcançável.
- Parcelamento com divisão inexata: R$ 100 em 3x não é 33,33 três vezes. Mostre onde foi o centavo.
- Estado vazio: nenhum cartão cadastrado ainda. Convide a cadastrar o primeiro, não explique o conceito de cartão de crédito.
- Mês passado é consultável, mas o presente e o futuro são o foco. Não abra em histórico.

### Técnico

React + Tailwind para o protótipo. Uma tela principal, com os detalhes de cada cartão abrindo em painel lateral ou modal — não crie rotas separadas por cartão. Monte com dados de exemplo realistas: três cartões com limites e datas diferentes, uns quinze gastos espalhados, quatro assinaturas incluindo uma anual, parcelamentos em andamento com prazos variados que terminem em meses diferentes. Isole as chamadas de dados numa camada só, para eu trocar mock por API depois.
