package br.dev.azimute.plataforma.web;

import br.dev.azimute.comum.ErroDeNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduz toda exceção que chega ao Spring MVC para {@link ErroApi} (RF06).
 * <p>
 * Nunca devolve stack trace, nome de classe ou mensagem técnica: com o backend exposto na internet,
 * isso é mapa para atacante. O detalhe vai para o log (SEC06).
 */
@RestControllerAdvice
public class TradutorDeErros {

    private static final Logger log = LoggerFactory.getLogger(TradutorDeErros.class);

    @ExceptionHandler(ErroDeNegocio.class)
    ResponseEntity<ErroApi> negocio(ErroDeNegocio e) {
        log.debug("Erro de negócio {}: {}", e.codigo(), e.getMessage());
        return resposta(statusDe(e.tipo()), new ErroApi(e.codigo(), e.getMessage(), e.detalhes()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErroApi> validacao(MethodArgumentNotValidException e) {
        var campos = new LinkedHashMap<String, Object>();
        for (FieldError erro : e.getBindingResult().getFieldErrors()) {
            campos.putIfAbsent(erro.getField(), erro.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors()
                .forEach(erro -> campos.putIfAbsent(erro.getObjectName(), erro.getDefaultMessage()));
        return dadosInvalidos(campos);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErroApi> validacaoDeParametros(HandlerMethodValidationException e) {
        var campos = new LinkedHashMap<String, Object>();
        e.getParameterValidationResults().forEach(resultado -> {
            var nome = resultado.getMethodParameter().getParameterName();
            resultado.getResolvableErrors()
                    .forEach(erro -> campos.putIfAbsent(nome, erro.getDefaultMessage()));
        });
        return dadosInvalidos(campos);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErroApi> malformada(HttpMessageNotReadableException e) {
        log.debug("Corpo ilegível: {}", e.getMessage());
        return resposta(HttpStatus.BAD_REQUEST,
                ErroApi.de("REQUISICAO_MALFORMADA", "O corpo da requisição não é um JSON válido para esta operação"));
    }

    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ErroApi> tipoErrado(TypeMismatchException e) {
        var nome = e.getPropertyName() == null ? "parâmetro" : e.getPropertyName();
        return resposta(HttpStatus.BAD_REQUEST, new ErroApi("PARAMETRO_INVALIDO",
                "Valor inválido para " + nome, Map.of("parametro", nome)));
    }

    /** Upload acima do limite (SEC04, T12 do SPEC do fluxo): 413, sem ecoar nada do arquivo. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErroApi> arquivoGrandeDemais(MaxUploadSizeExceededException e) {
        return resposta(HttpStatus.CONTENT_TOO_LARGE,
                ErroApi.de("ARQUIVO_GRANDE_DEMAIS", "O arquivo passa do limite de 2 MB"));
    }

    /** Erros do próprio Spring MVC (404, 405, 415, parâmetro ausente…) mantêm o status, no nosso formato. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErroApi> inesperado(Exception e) {
        if (e instanceof ErrorResponse erroDoSpring) {
            var status = erroDoSpring.getStatusCode();
            log.debug("Requisição rejeitada pelo Spring MVC ({}): {}", status, e.getMessage());
            return resposta(status, erroDeProtocolo(status));
        }
        log.error("Erro inesperado", e);
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR,
                ErroApi.de("ERRO_INTERNO", "Erro interno. Tente novamente mais tarde."));
    }

    private ResponseEntity<ErroApi> dadosInvalidos(Map<String, Object> campos) {
        return resposta(HttpStatus.UNPROCESSABLE_CONTENT, new ErroApi("DADOS_INVALIDOS",
                "Há campos inválidos na requisição", Map.of("campos", campos)));
    }

    private static ErroApi erroDeProtocolo(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErroApi.de("ROTA_NAO_ENCONTRADA", "Recurso não encontrado");
            case 405 -> ErroApi.de("METODO_NAO_SUPORTADO", "Método HTTP não suportado neste endereço");
            case 406 -> ErroApi.de("FORMATO_NAO_ACEITO", "Formato de resposta não suportado");
            case 415 -> ErroApi.de("TIPO_DE_CONTEUDO_NAO_SUPORTADO", "Tipo de conteúdo não suportado");
            default -> ErroApi.de("REQUISICAO_INVALIDA", "Requisição inválida");
        };
    }

    static HttpStatus statusDe(ErroDeNegocio.Tipo tipo) {
        return switch (tipo) {
            case VALIDACAO -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NAO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case CONFLITO -> HttpStatus.CONFLICT;
            case NAO_AUTENTICADO -> HttpStatus.UNAUTHORIZED;
            case BLOQUEADO -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }

    private static ResponseEntity<ErroApi> resposta(HttpStatusCode status, ErroApi corpo) {
        return ResponseEntity.status(status).body(corpo);
    }
}
