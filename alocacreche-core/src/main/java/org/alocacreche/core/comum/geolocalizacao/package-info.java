/**
 * Contrato de geolocalizacao compartilhado entre os modulos Inscricao &amp; Fila (motor
 * de score) e Matricula &amp; Vagas/Creches ({@code sugerirCrechesProximas}) — ver ADR
 * 0001. Fica num pacote comum, consumido por ambos, para nao criar dependencia de build
 * entre os dois modulos.
 */
package org.alocacreche.core.comum.geolocalizacao;
