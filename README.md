# Balanço de Branco Simulado

Balanço de branco por software para câmeras USB que não oferecem esse controle (ou cujo controle não funciona), no mesmo modelo do botão "White Balance" de uma CCU de endoscopia: mede uma referência branca, calcula os ganhos R, G, B **uma vez** e os mantém travados (sem AWB contínuo).

## Conteúdo

| Arquivo | O que é | Quando usar |
|---|---|---|
| `BalancoBranco.html` | App de um arquivo, roda no Edge/Chrome, sem instalação | Visualizar a imagem corrigida no Windows |
| `wb_simulado.py` | Protótipo em Python + OpenCV, com saída opcional para câmera virtual | Enviar o vídeo corrigido para Zoom/Teams/OBS, ou testar o algoritmo |

## Como usar

### App no navegador (`BalancoBranco.html`)

1. Abra o arquivo com duplo clique (Edge ou Chrome) e permita o acesso à câmera.
2. Escolha a câmera USB e a resolução.
3. Aponte para a referência branca preenchendo o quadrado amarelo.
4. Clique em **Balanço de branco** (ou tecle `W`).

Atalhos: `W` balanço · `R` reset · `O` ver original · `F` tela cheia.
Os ganhos ficam salvos por câmera no próprio navegador.

### Script Python (`wb_simulado.py`)

```
pip install opencv-python numpy pyvirtualcam
python wb_simulado.py --cam 0
python wb_simulado.py --cam 1 --width 1280 --height 720 --virtual
```

Teclas: `W` balanço · `R` reset · `S` salvar ganhos em `wb_ganhos.json` · `Q` sair.

Gerar executável no Windows:

```
pip install pyinstaller
pyinstaller --onefile --noconsole wb_simulado.py
```

O `.exe` fica em `dist\`.

## Algoritmo

1. Média de R, G, B na região central (ROI) sobre a referência branca.
2. Conversão sRGB → linear.
3. Ganhos: `ganho_c = alvo / lin_c`, com `alvo = min(R,G,B)` (padrão, todos os ganhos ≤ 1, nada estoura) ou `alvo = G`.
4. Aplicação por 3 LUTs de 256 entradas (sRGB → linear → × ganho → sRGB), portável para MCU/FPGA (3 × 256 bytes).

O balanço é **recusado** se a referência estiver saturada (> 2 % dos pixels ≥ 250 em algum canal) ou escura demais.

## Limitações

- A correção atua sobre a imagem já processada pela câmera (gamma, matriz de cor, compressão). É exata para neutros e aproximada para cores muito saturadas.
- Se o AWB da câmera não puder ser desligado, ele pode alterar a cor depois do balanço. O app tenta desligá-lo e indica se conseguiu.
- O `BalancoBranco.html` só exibe a imagem corrigida; para enviar a outros programas, use o script com `--virtual`.
