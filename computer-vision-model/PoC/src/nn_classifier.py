"""§5.5 — OPCIONAL: classificador leve treinado (GRU pequeno).

Só faz sentido DEPOIS que o baseline DTW estiver rodando e medido (evaluate.py).
Um GRU de 2 camadas e poucas dezenas de unidades, recebendo a mesma sequência de
landmarks normalizados. Deve ser avaliado com o MESMO protocolo leave-one-signer-out
do §5.4 e comparado diretamente com o DTW — só vale adotar se superar o baseline
de forma consistente.

Este arquivo é um STUB proposital: as sequências têm comprimentos diferentes
(número de frames varia por clipe), então a montagem do batch exige uma decisão
(padding + masking, ou reamostragem para comprimento fixo) que depende de olhar a
distribuição real de durações dos seus clipes. Documentado aqui para não bloquear
quem for implementar.

Passos sugeridos ao implementar:
  1. Carregar clips com dtw_classifier.carregar_dataset() (já achata para (T, 147)).
  2. Padronizar comprimento: pad para o maior T do batch com máscara, OU reamostrar
     todo clipe para T_fixo frames (ex.: 32) por interpolação temporal.
  3. Modelo Keras: Input(T, 147) -> Masking -> GRU(u) -> GRU(u) -> Dense(n_sinais, softmax).
  4. Treinar/avaliar em leave-one-signer-out (espelhe evaluate.leave_one_signer_out):
     em cada rodada, treinar só com as outras pessoas e testar na pessoa deixada de fora.
  5. Reportar acurácia média e comparar com o DTW na MESMA partição.
"""
from __future__ import annotations


def build_gru(n_sinais: int, t_fixo: int, n_features: int = 147,
              unidades: int = 64, camadas: int = 2):
    """Constrói o GRU do §5.5. Requer tensorflow (comentado no requirements.txt)."""
    try:
        from tensorflow import keras
        from tensorflow.keras import layers
    except ImportError as e:  # pragma: no cover
        raise SystemExit(
            "tensorflow não instalado. Descomente-o em requirements.txt para o §5.5."
        ) from e

    modelo = keras.Sequential(name="poc_gru")
    modelo.add(layers.Input(shape=(t_fixo, n_features)))
    modelo.add(layers.Masking(mask_value=0.0))
    for i in range(camadas):
        modelo.add(layers.GRU(unidades, return_sequences=(i < camadas - 1)))
    modelo.add(layers.Dense(n_sinais, activation="softmax"))
    modelo.compile(optimizer="adam",
                   loss="sparse_categorical_crossentropy",
                   metrics=["accuracy"])
    return modelo


def main() -> None:
    raise SystemExit(
        "nn_classifier é opcional (§5.5) e é um stub. Rode e meça o baseline DTW "
        "com evaluate.py primeiro; implemente o loop de treino leave-one-signer-out "
        "seguindo o passo a passo no topo deste arquivo."
    )


if __name__ == "__main__":
    main()
