# Ferrari Videonasofibrolaringoscopia — App Android (Etapa 1)

App mínimo **só da câmera**, conforme o briefing (`BRIEFING_APP_MEDICO.md`, Seção 4).
Objetivo: instalar num Android (celular via OTG ou no Xiaomi Pad 7), plugar a câmera USB
(OV6946 400×400 ou a câmera HD 1080p) e responder **sim/não: a câmera aparece?**

## Como gerar o APK (sem Android Studio)

1. Suba esta pasta inteira para um repositório no GitHub.
2. Abra a aba **Actions** → workflow **build-apk** (roda sozinho a cada push).
3. Na execução concluída, baixe o artifact **apk**
   (contém o `app-debug.apk`).
4. Instale o APK no Android ("instalar app de fonte desconhecida").

## Como testar

1. Ligue a câmera USB no aparelho (cabo OTG ou hub USB-C).
2. O Android pergunta se o app pode acessar o dispositivo USB → **OK/Permitir**
   (marque "sempre abrir este app quando conectado", se quiser).
3. O app abre: imagem ao vivo em tela cheia (por baixo) + o sistema web do médico
   já embutido, servido em `http://127.0.0.1:47832`.
4. No canto inferior aparece: resolução e formato detectados
   (ex.: `Câmera OK · 400×400 · NV21 · transmitindo em http://127.0.0.1:47832/camera.mjpeg`).
5. No sistema web, a aba Imagem mostra o ao vivo vindo do stream nativo
   (`<img src="/camera.mjpeg">`). Toque em **Foto** → salva JPG em
   `Android/data/br.com.ferrarimedical.vnfl/files/Dados/avulsos/` e avisa o nome na tela.

> O texto de status no rodapé também informa o formato que a câmera entregou
> (NV21 ou RGBA e a resolução real aberta — que é o que a Etapa 1 precisa confirmar).

## O que já está pronto desta base (reuso na Etapa 2)

- Servidor HTTP local (NanoHTTPD) servindo o sistema web **offline** dos assets + MJPEG + `/fs/*`
  (filesystem do consultório em `Dados/` dentro do app — mesmo contrato do programa de Windows).
- WebView em tela cheia carregando `http://127.0.0.1:47832` (secure context).
- Ponte JS `window.Android` (`info`, `stamp`, `foto`) e retorno `window.FV_FOTO_SALVA`.
- Ícone do app (do pacote) e `device_filter` USB (abre o app ao plugar a câmera).

## Observações técnicas

- Biblioteca UVC: `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3` (JitPack) —
  API verificada no código-fonte da lib; MJPEG preferido, fallback YUYV.
- Se a lib **não** abrir a OV6946 neste hardware, testar `UVCCamera` (saki4510t) antes
  de desistir — exatamente como o briefing pede (Seção 8).
- O sistema web foi tocado **o mínimo possível**: um único bloco "MODO NATIVO" detecta
  `window.Android` e troca só a origem da imagem (getUserMedia → stream MJPEG); o laço
  `frame()`, o processamento, o congelar e o gravar continuam os mesmos.
- Gravação de vídeo: no Android o `MediaRecorder` do WebView grava WebM (o MP4/WebM-VP9
  depende do codec do aparelho). Se o MP4 for obrigatório na Etapa 2, gravar no lado
  nativo (libausbc `captureStreamStart`) — decidir depois do teste da Etapa 1.
