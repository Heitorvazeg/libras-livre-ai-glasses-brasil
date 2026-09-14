import copy
import unittest

from auditar_m9_loso import FT, PESSOAS, resumir_folds


def fixture():
    rotulos = sorted(["acontecer", "aluno", "amarelo", "america", "aproveitar", "bala", "banco",
                      "banheiro", "barulho", "cinco", "conhecer", "espelho", "esquina", "filho",
                      "maca", "medo", "ruim", "sapo", "vacina", "vontade"])
    y = [i for i in range(20) for _ in range(5)]
    return [{"teste": p, "validacao": PESSOAS[(i + 1) % 8], "rodada": i + 1,
             "rotulos": rotulos, "args": FT | {"semente": 20260917}, "melhor_epoca": 99,
             "verdadeiros": y.copy(), "predicoes": y.copy(), "acuracia": 1.0} for i, p in enumerate(PESSOAS)]


class TestM9(unittest.TestCase):
    def test_contagens_pessoa_sinal_sem_inferir_frase(self):
        fs = fixture()
        f = fs[2]
        i, j = f["rotulos"].index("filho"), f["rotulos"].index("medo")
        f["predicoes"][i * 5] = j
        f["acuracia"] = .99
        r = resumir_folds(fs[::-1])
        self.assertEqual(r["acertos"], 799)
        self.assertEqual(r["roteiro_acertos"], 239)
        self.assertEqual(r["pessoas"]["M05"]["roteiro"]["filho"], 4)
        self.assertEqual(r["confusoes"], [{"verdadeiro": "filho", "predito": "medo", "n": 1}])

    def test_rejeita_folds_incompletos_duplicados(self):
        fs = fixture()
        for bad in (fs[:1], fs[:-1] + [fs[0]]):
            with self.assertRaises(ValueError):
                resumir_folds(bad)

    def test_rejeita_mistura_receita_seed_e_particao(self):
        for change in (lambda f: f["args"].update(semente=20260918),
                       lambda f: f["args"].update(ossos=False),
                       lambda f: f.update(validacao="M01"),
                       lambda f: f.update(melhor_epoca=121)):
            fs = copy.deepcopy(fixture())
            change(fs[0])
            with self.assertRaises(ValueError):
                resumir_folds(fs)

    def test_rejeita_indices_denominador_e_acuracia(self):
        for change in (lambda f: f["predicoes"].pop(),
                       lambda f: f["predicoes"].__setitem__(0, -1),
                       lambda f: f["verdadeiros"].__setitem__(0, 1),
                       lambda f: f.update(acuracia=.99)):
            fs = fixture()
            change(fs[0])
            with self.assertRaises(ValueError):
                resumir_folds(fs)


if __name__ == "__main__":
    unittest.main()