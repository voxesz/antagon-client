# Antagon Client

Client de Minecraft para **macOS e Windows**, com mods de PvP, cosméticos, amigos e rádio.
Escolha entre **Minecraft 1.8.9 com Forge** e **26.x com Fabric**, personalize sua experiência e jogue com sua comunidade.

![Launcher](docs/launcher.png)

## Download

Baixe a versão mais recente em [Releases](https://github.com/voxesz/antagon-client/releases/latest):

- **macOS:** `Antagon-Client-macOS.zip`. Na primeira vez, clique com o botão direito no app → **Abrir**. Se o macOS disser
  que o app está danificado, rode `xattr -cr "/Applications/Antagon Client.app"` no Terminal.
- **Windows:** `Antagon-Client-Windows.zip`. Extraia e abra `Antagon Client.exe`. Se o SmartScreen avisar, clique em
  **Mais informações → Executar assim mesmo**.

O app não é assinado por uma conta paga da Apple ou da Microsoft, por isso os avisos na primeira abertura.

A partir da versão 0.1.2, o launcher confere se há versão nova no GitHub ao abrir e mostra o botão **Atualizar**.

| Amigos e chat | Mods no jogo |
| --- | --- |
| ![Amigos](docs/friends.png) | ![Menu](docs/in-game-menu.png) |

| Editor de HUD |
| --- |
| ![Editor de HUD](docs/hud-editor.png) |

## Recursos

**Launcher**

- Conta Microsoft original ou nick offline
- Perfis separados para Minecraft 1.8.9 + Forge e a versão estável mais recente da linha 26.x + Fabric
- Instala o jogo numa pasta isolada, preservando outros launchers, mundos e texturas
- Suporte a macOS Apple Silicon e Intel, com Java e bibliotecas nativas adequados à arquitetura
- Windows 10/11 x64, com instalação automática do Java necessário para cada versão
- Tela inicial com imagem personalizável, configurações de jogo e cosméticos em destaque
- Instalação guiada do OptiFine para 1.8.9; Fabric e Sodium no perfil 26.x
- Amigos: adicionar pelo nick, ver o que cada um está jogando, entrar no mesmo servidor e conversar (conta Microsoft)
- Atualização automática pelas Releases do GitHub
- Discord Rich Presence integrado, com controle de privacidade do servidor
- Loja de cosméticos com ANTAGOIN$: capas, Coroa Antagon em 3D e inventário associado à conta Microsoft (itens retirados
  da loja continuam no inventário de quem já tem)
- Notificações de amigo online, pedido recebido, pedido aceito e mensagem no launcher, no jogo e no sistema
- Tag Antagon no chat, logo ao lado do ping no tab (dourada para admins), e cosméticos visíveis para outros jogadores com
  o client e sessão de comunidade ativa

**Mods no Minecraft 1.8.9** (configuráveis no menu do client). O botão Antagon no menu inicial abre as configurações do launcher. No Esc,
os atalhos acima de **Abrir para LAN** abrem Configurações, Amigos e Loja; administradores também veem Admin.

O menu usa categorias, rolagem suave e altura ajustada à janela. Cabeçalho e rodapé ficam fixos; use a roda do mouse,
as setas ou Page Up/Page Down para explorar a lista. As opções e o editor de HUD continuam acessíveis em qualquer tamanho de interface.

A **Rádio Antagon** usa o player do próprio launcher, no macOS e no Windows. A aba **Rádio** oferece estações
ao vivo sincronizadas pelo servidor e playlists com reprodução individual. No jogo 1.8.9, o módulo Rádio mostra
música, artista, capa e progresso; **Rádio → Abrir rádio**, no menu de mods, abre o catálogo do launcher. Os atalhos
de pausa, anterior, próxima e volume controlam esse mesmo player. Anterior, próxima e busca ficam disponíveis
nas playlists; ao voltar a ouvir uma rádio, a reprodução acompanha a transmissão atual.

Administradores usam **Admin → Rádio** para enviar MP3, M4A, OGG ou WAV (até 50 MB / 1 hora), escolher capas,
criar rádios e playlists e reordenar suas filas. Para ampliar o catálogo, envie músicas autorizadas, adicione-as
à seleção pelo **+** e salve. A Rádio Antagon mistura os estilos escolhidos pelo admin. A programação se repete
e suas alterações entram em até 45 segundos. Os arquivos ficam no Supabase Storage (`radio-media`); o banco
guarda apenas o catálogo e a programação. Nenhuma chave de administrador é distribuída com o app.

| HUD | Combate | Utilidades |
| --- | --- | --- |
| FPS, CPS, Ping | Reach Display | Toggle Sprint |
| Keystrokes | Combo Counter | Perspective (freelook) |
| Coordenadas, Relógio | Hitbox | Chat (horário, empilhar, tamanho) |
| Rádio Antagon | Hit Color | No Titles |
| | Crosshair, Item Size | No Hurt Cam |

Todos os módulos do HUD podem ser arrastados e redimensionados pelos quatro cantos no editor.

**Item Size** ajusta o tamanho dos itens na mão em primeira pessoa, incluindo espadas, de **25% a 150%**.
Ative o módulo e use **Tamanho na mão**; o valor inicial é 70%. Desativar restaura o tamanho normal.
O ajuste é visual, preserva os ícones do inventário e funciona com OptiFine.

O conjunto de mods próprios do Antagon, incluindo Item Size e cosméticos no jogo, é do perfil **1.8.9**.
O perfil **26.x** usa Fabric e mods compatíveis; ainda não inclui o HUD próprio do Antagon.

No módulo **CPS**, escolha **ambos**, **esquerdo** ou **direito** em **Botões do mouse** e use
**Mostrar CPS** para ligar ou desligar o sufixo. O padrão continua `0 | 0 CPS`.

Em **Admin → Itens → Editar**, administradores alteram nome, preço, disponibilidade na loja e
**Destacar na tela inicial**. A home mostra somente itens em destaque que estejam disponíveis na loja.
**Excluir** também funciona para as capas iniciais e a coroa e remove o item dos inventários após confirmação.
A edição mantém a identidade do item e quem já o possui.

Os próprios cosméticos são preparados antes da abertura do Minecraft 1.8.9. Entradas e saídas de jogadores
atualizam a sincronização sem esperar a consulta periódica. As texturas são gerenciadas pelo próprio Minecraft;
o launcher preserva os pacotes selecionados e sua ordem.

## Desenvolvimento

Requisitos: Node.js 22+ e JDK 17+ (só para compilar o mod). Os testes de interface e do jogo usam perfis isolados em `build/`, sem modificar seus mundos ou preferências.

```sh
npm install
npm run build:mod
npm start
```

| Comando | O que faz |
| --- | --- |
| `npm run install:game` | Baixa e instala Minecraft 1.8.9 + Forge sem abrir o launcher |
| `npm test` | Testes de estado, downloads, privacidade e protocolo do Discord |
| `npm run test:java` | Testes de reflexão e limites do menu em diferentes resoluções |
| `npm run test:discord` | Valida o Rich Presence com o Discord local aberto |
| `npm run test:ui` | Valida a home, navegação, configurações e administração |
| `npm run test:client-fixes` | Testa mensagens, edição, exclusão e destaques dos cosméticos |
| `npm run test:radio` | Valida o player de rádios e playlists |
| `npm run test:game` | Abre o jogo, testa menu, editor e mods e salva screenshots |
| `npm run package:mac` | Compila o mod e gera o `.app` em `release/` |
| `npm run package:win` | Compila o mod e gera a versão Windows x64 em `release/` |
| `npm run format` | Formata o código (Prettier) |

O build de macOS também atualiza uma cópia do app na Área de Trabalho.

## Discord

O Rich Presence usa o aplicativo Antagon Client (`1554534012670836746`) e se conecta ao Discord instalado no computador
por IPC local. Não solicita senha, token de usuário ou bot. Ative ou desative em **Configurações → Discord**.
O status distingue launcher, menu, singleplayer e partida; o nome do servidor só aparece quando **Compartilhar o servidor** está ativo.
A conexão é restabelecida se o Discord for aberto depois do launcher, e a presença é removida ao desativar ou fechar o client.

Implementação baseada no [protocolo RPC oficial](https://discord.com/developers/docs/topics/rpc).

## Comunidade

Amigos, status e chat usam o [Supabase](https://supabase.com) (`supabase/`). O login confirma a conta Microsoft na API
da Mojang (`supabase/functions/minecraft-auth`) antes de criar a sessão; as regras de acesso ficam no próprio banco
(`supabase/migrations`): só amigos veem o seu status, só dá para mandar mensagem para amigos e ninguém cria amizade em
nome de outro. A chave em `electron/community.cjs` é a chave pública do projeto.

## Loja e pagamentos

A migração `supabase/migrations/20260930000000_cosmetics.sql` cria catálogo, carteira, inventário, equipamento e
as operações atômicas de compra. O cliente nunca grava saldo diretamente. O launcher envia ao banco apenas os UUIDs
da lista de jogadores; o jogo recebe um arquivo local com tags e capas, sem receber tokens da conta. A capa Antagon
equipada também assume a textura de capa do OptiFine durante a partida.

Para ativar pagamentos, aplique as migrações no projeto Supabase e publique as funções `coin-checkout` e `coin-webhook`.
Configure os segredos `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` e `CHECKOUT_RETURN_URL` (uma URL HTTPS de retorno após
o Checkout). Registre na Stripe um endpoint para `https://<project-ref>.supabase.co/functions/v1/coin-webhook` com os
eventos `checkout.session.completed` e `checkout.session.async_payment_succeeded`. A função do webhook tem
`verify_jwt = false` porque a Stripe não envia JWT do Supabase; ela valida a assinatura do corpo recebido. Os pacotes
custam R$ 4,90 (100 ANTAGOIN$), R$ 19,90 (550) e R$ 39,90 (1.200). As capas custam 100 ANTAGOIN$ e a coroa, 250.

O pagamento é concluído no navegador. Use **Atualizar saldo** na loja depois de voltar. A tag no chat é adicionada a
mensagens nos formatos `<Nick>` e `Nick:` (com prefixo opcional `[Rank]`); servidores com outros formatos podem não exibi-la. O tab usa a tag para
qualquer jogador autenticado com o client ativo. Contas offline não participam da loja nem publicam cosméticos.

A migração `20260930010000_admin.sql` concede o cargo de proprietário ao UUID verificado da conta **Voxesz** e cria o
painel **Admin**. O proprietário pode nomear ou remover outros administradores. Administradores podem buscar jogadores
registrados pelo nick, banir ou desbanir contas, definir o saldo de ANTAGOIN$ e conceder ou remover cosméticos. A aba **Criar capa** monta uma capa com
texto e imagem (face visível 5:8, textura final 1024 × 512 enviada ao bucket público `cosmetics`) e a coloca na loja,
envia para um jogador ou deixa só no inventário do admin; a aba **Itens** tira ou recoloca itens na loja. Todas as
mudanças ficam registradas em `admin_audit`; as permissões são verificadas no banco. O banimento bloqueia login,
comunidade, loja e jogo com a conta Microsoft no launcher distribuído. O modo offline e cópias modificadas do client
não podem ser bloqueados com segurança sem um servidor de jogo ou serviço de autorização obrigatório.

## Estrutura

```
electron/   processo principal: login, instalação, execução do jogo, comunidade e atualização
ui/         interface do launcher, loja, administração e player de rádio
mod-src/    mod Forge (HUD, menu, mods) e coremod (Item Size, Hit Color e outros ajustes)
supabase/   banco e função de login da comunidade
scripts/    build do mod, testes e empacotamento
assets/     fonte, ícones e natives arm64 (ver assets/natives-arm64/SOURCES.txt)
```

O mod não distribui código do Minecraft: acessa o jogo por reflexão com nomes SRG do Forge 1.8.9 e compila contra
um stub mínimo de `GuiScreen`.

## Licenças

- Código: [MIT](LICENSE)
- Pixelify Sans: SIL Open Font License ([assets/PixelifySans-LICENSE.txt](assets/PixelifySans-LICENSE.txt))
- Natives LWJGL/JInput arm64: BSD ([assets/natives-arm64/SOURCES.txt](assets/natives-arm64/SOURCES.txt))

Projeto independente, não afiliado à Mojang ou à Microsoft. Minecraft é marca registrada da Mojang Synergies AB.
