# Arquitetura

O Ciclo Platform é um monorepo com cinco serviços Spring Boot e dois aplicativos Angular. Cada serviço de negócio segue Onion Architecture: `domain` não depende de frameworks; `application` coordena casos de uso e declara portas; `infrastructure` implementa persistência e integrações; `presentation` expõe HTTP e mensageria.

## Serviços

- `gateway-service`: entrada única, correlação, CORS, cookies de acesso e proteção CSRF.
- `identity-service`: cadastro, verificação de e-mail, login, rotação de refresh token, recuperação de senha e administração de usuários.
- `study-service`: concursos, editais, planos, sessões, flashcards, simulados e progresso.
- `ai-service`: roteamento entre OpenAI, Anthropic e Gemini, segredos criptografados, limites, bloqueios, custos e auditoria.
- `admin-service`: dashboard agregado e configurações gerais/de segurança.

Os bancos são isolados por serviço. RabbitMQ transporta solicitações e resultados de IA, Redis mantém contadores de consumo e MinIO armazena os documentos. O gateway é o único endpoint público da API.

## Frontend

`web` atende estudantes e `admin` atende operadores. Ambos usam Angular standalone, Tailwind CSS 4 e as bibliotecas compartilhadas `ui`, `auth` e `api-client`.

## Segurança

JWT HMAC é usado localmente, com papéis convertidos explicitamente para authorities do Spring Security. Access e refresh tokens são entregues em cookies `HttpOnly`; o gateway aplica CSRF em operações mutáveis. Chaves de provedores de IA são cifradas com AES-GCM antes da persistência.
