// O alvo do proxy vem do ambiente de propósito. Com o target fixo em localhost:8080, qualquer
// outro processo ocupando a porta 8080 na máquina (um dev server de outro projeto, por exemplo)
// fazia o proxy responder 504 em toda chamada /api — a tela abria, mas sem nenhum dado, e o
// primeiro sintoma era "a página de IA está vazia".
const target = process.env.GATEWAY_URL ?? 'http://localhost:8080';

module.exports = {
  '/api': {
    target,
    secure: false,
    changeOrigin: true,
    logLevel: 'warn',
  },
};
