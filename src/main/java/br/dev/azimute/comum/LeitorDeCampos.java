package br.dev.azimute.comum;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Lê os campos de um corpo de PATCH recebido como mapa. Campo ausente ou nulo devolve {@code null} (não muda);
 * campo com formato errado guarda a mensagem e também devolve {@code null}, para todos os erros saírem de uma vez.
 * <p>
 * No fim, {@link #conferir(Function)} lança o erro do módulo, com a sua {@link ErroDeDadosInvalidos}. Os erros
 * saem na ordem em que os campos foram lidos, e só o primeiro de cada campo fica.
 */
public final class LeitorDeCampos {

    private static final String TEXTO = "deve ser texto";
    private static final String TEXTO_NAO_VAZIO = "deve ser texto não vazio";
    private static final String BOOLEANO = "deve ser true ou false";
    private static final String DATA = "deve estar no formato AAAA-MM-DD";
    private static final String COMPETENCIA = "deve estar no formato AAAA-MM";
    private static final String POSITIVO = "deve ser um valor positivo com até 2 casas decimais";
    private static final String NAO_NEGATIVO = "deve ser um valor não negativo com até 2 casas decimais";
    private static final String ID = "deve ser um ID válido";

    private final Map<String, ?> corpo;
    private final Map<String, Object> erros = new LinkedHashMap<>();

    public LeitorDeCampos(Map<String, ?> corpo) {
        this.corpo = corpo;
    }

    /** Qualquer texto, inclusive vazio. */
    public String texto(String campo) {
        var valor = corpo.get(campo);
        if (valor == null || valor instanceof String) {
            return (String) valor;
        }
        return erro(campo, TEXTO);
    }

    public String textoNaoVazio(String campo) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto && !texto.isBlank()) {
            return texto;
        }
        return erro(campo, TEXTO_NAO_VAZIO);
    }

    public Boolean booleano(String campo) {
        var valor = corpo.get(campo);
        if (valor == null || valor instanceof Boolean) {
            return (Boolean) valor;
        }
        return erro(campo, BOOLEANO);
    }

    public LocalDate data(String campo) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto) {
            try {
                return LocalDate.parse(texto);
            } catch (DateTimeParseException e) {
                // segue para o erro de campo abaixo
            }
        }
        return erro(campo, DATA);
    }

    public Competencia competencia(String campo) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto) {
            try {
                return Competencia.de(texto);
            } catch (IllegalArgumentException e) {
                // segue para o erro de campo abaixo
            }
        }
        return erro(campo, COMPETENCIA);
    }

    /** Maior que zero e no máximo 2 casas, sem arredondar. Aceita número ou texto, como o JSON chegar. */
    public Dinheiro dinheiroPositivo(String campo) {
        var dinheiro = dinheiro(campo);
        if (dinheiro == null || dinheiro.positivo()) {
            return dinheiro;
        }
        return erro(campo, POSITIVO);
    }

    public Dinheiro dinheiroNaoNegativo(String campo) {
        return dinheiroNaoNegativo(campo, NAO_NEGATIVO);
    }

    /** Zero ou mais e no máximo 2 casas, com a mensagem do módulo. */
    public Dinheiro dinheiroNaoNegativo(String campo, String mensagem) {
        var dinheiro = dinheiro(campo, mensagem);
        if (dinheiro == null || !dinheiro.negativo()) {
            return dinheiro;
        }
        return erro(campo, mensagem);
    }

    /** O ID vem sempre como texto; a fábrica é a do tipo (por exemplo {@code ContaId::new}). */
    public <I> I id(String campo, Function<UUID, I> fabrica) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto) {
            try {
                return fabrica.apply(UUID.fromString(texto));
            } catch (IllegalArgumentException e) {
                // segue para o erro de campo abaixo
            }
        }
        return erro(campo, ID);
    }

    /** Com a mensagem padrão: {@code deve ser um de [A, B, C]}. */
    public <E extends Enum<E>> E enumeracao(String campo, Class<E> tipo) {
        return enumeracao(campo, tipo, umDe(tipo));
    }

    /** Como {@link #enumeracao(String, Class)}, mas ausente ou nulo também é erro, com a mesma mensagem. */
    public <E extends Enum<E>> E enumeracaoObrigatoria(String campo, Class<E> tipo) {
        var valor = enumeracao(campo, tipo);
        return valor != null ? valor : erro(campo, umDe(tipo));
    }

    private static String umDe(Class<? extends Enum<?>> tipo) {
        return "deve ser um de " + Arrays.stream(tipo.getEnumConstants()).map(Enum::name).toList();
    }

    public <E extends Enum<E>> E enumeracao(String campo, Class<E> tipo, String mensagem) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto) {
            for (var constante : tipo.getEnumConstants()) {
                if (constante.name().equals(texto)) {
                    return constante;
                }
            }
        }
        return erro(campo, mensagem);
    }

    /** Registra um erro de regra (campo obrigatório, combinação proibida). Se o campo já tem erro, fica o primeiro. */
    public <T> T erro(String campo, String mensagem) {
        erros.putIfAbsent(campo, mensagem);
        return null;
    }

    public boolean temErro(String campo) {
        return erros.containsKey(campo);
    }

    /** Lança o erro do módulo com todos os campos inválidos, se houver algum. */
    public void conferir(Function<Map<String, Object>, ? extends ErroDeDadosInvalidos> erro) {
        if (!erros.isEmpty()) {
            throw erro.apply(new LinkedHashMap<>(erros));
        }
    }

    private Dinheiro dinheiro(String campo) {
        return dinheiro(campo, POSITIVO);
    }

    private Dinheiro dinheiro(String campo, String mensagem) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof Number || valor instanceof String) {
            try {
                return Dinheiro.exato(valor.toString());
            } catch (IllegalArgumentException e) {
                // segue para o erro de campo abaixo
            }
        }
        return erro(campo, mensagem);
    }
}
