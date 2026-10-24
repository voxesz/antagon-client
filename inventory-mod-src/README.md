# Antagon Inventory

Mod próprio para Minecraft Java 26.3 + Fabric Loader 0.20.1 e Fabric API 0.165.0 ou posteriores compatíveis com 26.3. Não depende de Kotlin, libIPN ou Inventory Profiles Next. É instalado no Antagon do dono do mundo e usa o servidor integrado para o envio direto, inclusive em mundos hospedados pelo Essential. Os convidados podem continuar jogando normalmente sem instalar este mod.

No inventário existem dois botões: **Organizar** e **Mandar pros baús**. Ficam à direita do livro de receitas, centralizados com ele, acompanhando a posição do inventário quando o livro é aberto ou a escala é alterada. No baú, os ícones **Pegar equivalentes**, **Pegar tudo** e **Organizar baú** permanecem na barra superior. Os cinco botões têm 14×14 pixels. As descrições aparecem ao passar o mouse; os nomes completos continuam disponíveis ao narrador do jogo. Todos os textos do mod, incluindo descrições, mensagens, configuração e idioma alternativo, ficam em português mesmo quando o Minecraft usa outro idioma.

**Shift + clique no ícone de Mandar pros baús** abre o ajuste de alcance. O controle vai de 1 a 64 blocos e começa em 8. **Concluir** ou Esc salva e retorna ao inventário. A preferência fica em `config/antagon-inventory.properties`, no perfil 26.3, e é recarregada nas próximas inicializações. Valores inválidos usam o padrão; tanto o cliente quanto o servidor limitam o intervalo permitido.

Com um baú aberto e o cursor vazio, segure o botão esquerdo sobre um item das três linhas do inventário e passe por outros espaços para guardá-los no baú. Cada espaço é transferido uma vez por gesto; a hotbar fica protegida. Um clique simples, sem passar por outro espaço, continua pegando o item normalmente ao soltar o botão. Arrastar com um item já no cursor e Shift+clique continuam usando o comportamento normal do jogo. Esc cancela transferências pendentes.

A organização agrupa pilhas e ordena pelo nome dos itens no idioma do jogo. As operações usam as três linhas do inventário; hotbar, equipamentos, crafting e mão secundária não são alterados. A hotbar também serve como referência para “Pegar equivalentes”. Variantes com componentes diferentes (encantamentos, nome, durabilidade, conteúdo etc.) não são misturadas.

“Mandar pros baús” guarda os itens diretamente nos baús dentro do alcance configurado, mantendo a tela do inventário aberta. Inclui baús duplos, de armadilha e de cobre; o par de um baú duplo é visitado uma vez. Procura apenas entidades de bloco em chunks já carregados, sem percorrer todos os blocos do raio ou carregar novas áreas. Ignora baús trancados e baús com loot ainda não gerado e não exige que a tampa esteja livre. Baús cheios são ignorados e itens sem destino equivalente permanecem no inventário.

O botão envia a tarefa ao servidor interno do próprio jogo. A tarefa confirma o dono do mundo, a dimensão, o estado do jogador, a ausência de outro menu e o cursor vazio; depois executa a movimentação na thread do servidor. Não abre interfaces ou tampas, não usa cliques simulados no baú, não muda o item selecionado e não exige mão vazia. Cada transferência é planejada antes de escrever nos inventários, valida o resultado e restaura o estado anterior se um contêiner rejeitar a mudança. O servidor sincroniza os itens normalmente com o dono e com convidados que estejam vendo um baú.

Esse envio direto é exclusivo do mundo hospedado pelo usuário. Em um servidor separado ou no mundo de outro anfitrião, mostra uma mensagem em vez de tentar acessar o armazenamento remoto. As outras quatro ações e o gesto de arrastar continuam usando a interação normal do cliente.

Esc cancela o que estiver pendente. Nas operações por clique, se houver um item no cursor, ele é devolvido ao espaço de origem antes de fechar. Cliques manuais e atalhos de movimentação de itens ficam bloqueados durante uma operação dos botões. Organização e retirada do baú usam cliques PICKUP do protocolo normal; o gesto de arrastar usa QUICK_MOVE, equivalente ao Shift+clique. Ambos têm pausas entre cliques e verificações de estado. O envio direto é feito exclusivamente na thread do servidor integrado; nenhuma escrita em contêineres do mundo ocorre na thread do cliente.

## Compilar e instalar

Na raiz do repositório, com o perfil 26.3 já preparado no Antagon Client e JDK 25 ou posterior:

```sh
python3 scripts/build-inventory-mod.py
python3 scripts/build-inventory-mod.py --install
```

O resultado é `assets/antagon-inventory-26.3.jar`. O segundo comando também instala na instância `minecraft-26.3`, arquiva o IPN anterior e as dependências que não forem usadas por outros mods, e preserva os demais JARs. Mantém recibo e cópias de segurança em `antagon-mod-backups/antagon-inventory/`. Reinicie o jogo para carregar.

O build compila contra o Minecraft/Fabric reais instalados e verifica o alvo do mixin. Roda 1.500 inventários aleatórios no simulador de cliques, testes do gesto de arrastar, da persistência e dos limites da configuração, e mais 300 inventários com as classes reais de itens e contêineres do Minecraft 26.3. Cobre conservação de itens, componentes distintos, limites de pilha, baús duplos, inventário cheio, hotbar protegida, cancelamento e restauração após falha. Verifica também se o idioma alternativo contém os mesmos textos em português. Esses testes não substituem a validação visual no jogo e a conferência no mundo hospedado pelo Essential.
