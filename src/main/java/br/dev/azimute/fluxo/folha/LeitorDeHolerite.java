package br.dev.azimute.fluxo.folha;

/**
 * Um leitor por layout de holerite (RNF04): trocar ou acrescentar layout não mexe no processamento, que parte sempre
 * do {@link HoleriteLido}.
 */
interface LeitorDeHolerite {

    /** @throws HoleriteIlegivelException com o campo que falhou */
    HoleriteLido ler(byte[] pdf);
}
