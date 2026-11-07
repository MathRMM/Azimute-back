# Prompt para o Claude Design — Simulador interativo de entrada

> Adendo à tela de calculadora de financiamento descrita no `prompt-claude-design.md`. Cole o bloco abaixo.

---

## PROMPT

Preciso adicionar um controle interativo à tela de calculadora de financiamento. Hoje ela recebe quatro números e devolve a taxa. Quero que, depois disso, a pessoa possa **arrastar o valor da entrada e ver a parcela se ajustar em tempo real**.

### A lógica

Uma vez calculada a taxa de uma proposta, ela vira referência. Segurando essa taxa fixa e variando a entrada, dá para estimar a parcela de qualquer cenário sem pedir nova simulação à concessionária.

```
valor financiado = valor do bem − entrada
parcela estimada = fórmula Price sobre o financiado, na taxa já apurada
```

Todo o cálculo roda no cliente, sem rede. É o que torna o arrasto instantâneo e o que faz a tela funcionar dentro de uma concessionária com sinal ruim.

### O controle

**Entrada** é o eixo principal: um slider de R$ 0 até o valor do bem, acompanhado de campo numérico para precisão. Slider serve para explorar; campo serve para cravar um número. Em tela pequena, adicione botões de incremento — arrastar com o polegar não acerta R$ 7.470.

Ofereça alternância entre **reais e percentual do bem**. Financeira raciocina em "30% de entrada"; a pessoa raciocina em "tenho R$ 7.470". As duas leituras precisam estar disponíveis sem conta mental.

**Prazo** é controle segmentado, não slider — 12, 24, 36, 48, 60. São opções discretas que a financeira oferece, não um contínuo.

O arrasto atualiza, ao vivo: parcela, total pago, total de juros e taxa anual equivalente.

### O ponto de ancoragem — o detalhe mais importante

A proposta original precisa estar **marcada visivelmente no slider**. É o único ponto da curva onde o número é fato; todo o resto é extrapolação.

E a extrapolação piora conforme se afasta:

- Financeira costuma oferecer taxa melhor com entrada maior — então a estimativa fica **pessimista** quando você arrasta para cima
- Tarifas fixas embutidas (cadastro, IOF, registro) pesam proporcionalmente mais sobre um financiado menor — então a estimativa fica **otimista** quando você arrasta para cima

Os dois efeitos puxam em direções opostas e não se cancelam de forma previsível. A consequência de projeto: **a confiança da estimativa precisa degradar visualmente conforme a distância da âncora.** Perto do ponto original, o número é firme. Longe, ele é uma indicação.

Resolva isso do jeito que fizer sentido no seu desenho — mas não apresente uma parcela extrapolada com a mesma autoridade visual da parcela real. Isso não é escrúpulo: é a diferença entre chegar na concessionária com um argumento e chegar com um erro.

### Uso na negociação

Adicione um modo de conferência: a pessoa informa entrada e parcela de uma **nova** proposta, e a tela compara com o que a taxa de referência previa.

> Pela taxa da proposta anterior, R$ 9.000 de entrada em 36x daria R$ 730.
> Esta proposta cobra R$ 810. A taxa embutida aqui é 4,6% ao mês — pior.

Esse é o momento em que a tela justifica existir. Trate-o como funcionalidade central, não como extra escondido.

### Ligação com a reserva

A pessoa está juntando dinheiro para comprar à vista, e a dúvida real não é "financiar ou não" — é **"vale esperar até ter tudo, ou entrar com o que tenho em algum momento?"**

Então, ao lado de cada posição do slider, mostre **quando aquela entrada estará disponível** segundo o plano de reserva. Arrastar para R$ 9.000 responde duas coisas ao mesmo tempo: qual seria a parcela, e que isso só acontece em novembro de 2027.

E, para a posição escolhida, mostre o custo de não esperar: quanto de juros aquele financiamento custa, contra zero se ela aguardar o valor integral. Em reais, não em percentual — é o número que decide.

### Estados de borda

- Entrada igual ao valor do bem: parcela zero, juros zero. Não é caso de erro, é o cenário à vista — e é o alvo dela. Mostre com destaque, não como estado vazio.
- Entrada zero: financiamento integral. Válido, mas muitas financeiras exigem mínimo. Permita configurar um piso e sinalizar abaixo dele.
- Sem proposta calculada ainda: o slider não tem taxa para usar. Peça a primeira simulação antes de liberar o controle, deixando claro que ele depende dela.

### Comportamento

O arrasto precisa ser **fluido**, sem espera e sem indicador de carregamento. A matemática são dezenas de iterações de uma bisseção — microssegundos. Se travar, o problema é renderização, não cálculo.

Números não devem "pular" durante o arrasto a ponto de ficarem ilegíveis. Considere suavizar a transição do valor exibido sem atrasar a resposta do controle.

### Direção visual

Segue a mesma linha da tela de calculadora: densidade alta, tipografia tabular, alinhamento vertical dos valores em reais, pt-BR (R$ 1.234,56), claro e escuro.

Esta é a única tela do app que pode ser incisiva. Uma parcela que implique taxa acima de 3% ao mês deve parecer cara na tela — sem sermão e sem emoji, mas sem neutralidade também.

Evite gradiente roxo, ilustração e o visual genérico de simulador de banco. A metáfora vem do assunto: um valor deslizando entre dois extremos, com um ponto de verdade no meio e incerteza crescente nas bordas.

### Técnico

React + Tailwind para o protótipo, cálculo local, zero chamadas de rede durante o arrasto. A função de parcela é a fórmula Price padrão; a taxa vem pronta do cálculo anterior. Monte com um exemplo realista carregado: moto de R$ 19.990, entrada de R$ 6.000, 24x de R$ 930,42 — taxa apurada de 4,14% ao mês.
