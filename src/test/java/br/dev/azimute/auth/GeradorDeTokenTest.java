package br.dev.azimute.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SEC02: 256 bits aleatórios em Base64 URL-safe; o banco guarda só o SHA-256. */
class GeradorDeTokenTest {

    @Test
    void token_tem_256_bits_em_base64_url_sem_padding_e_nao_se_repete() {
        var um = GeradorDeToken.novo();
        var outro = GeradorDeToken.novo();

        assertThat(um).matches("[A-Za-z0-9_-]{43}");
        assertThat(outro).isNotEqualTo(um);
    }

    @Test
    void hash_e_o_sha256_em_hexadecimal() {
        // Vetor de teste do FIPS 180-2
        assertThat(GeradorDeToken.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
