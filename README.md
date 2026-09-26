# Ciclo Platform

Reimplementação do Ciclo em Java 17, Spring Boot e Angular.

Este monorepo contém cinco serviços Spring Boot, dois aplicativos Angular e a infraestrutura local necessária para desenvolvimento.

## Início rápido

```bash
cp .env.example .env
docker compose up --build
```

- Web: http://localhost:4200
- Admin: http://localhost:4201
- Gateway/API: http://localhost:8080
- Mailpit: http://localhost:8025
- RabbitMQ: http://localhost:15672

O administrador local inicial é criado pelas variáveis `BOOTSTRAP_ADMIN_EMAIL` e `BOOTSTRAP_ADMIN_PASSWORD`.

## E-mail e confirmação de cadastro

No ambiente local, as mensagens são capturadas pelo Mailpit e não são encaminhadas
para provedores externos. Acesse http://localhost:8025 para abrir os links de
confirmação e recuperação de senha.

A exigência de confirmação pode ser ligada ou desligada em **Admin > Configurações
> Segurança**. A mesma tela permite conferir a configuração de entrega e enviar uma
mensagem de teste. Credenciais SMTP continuam restritas ao `.env` ou ao gerenciador
de secrets da implantação.

Para usar Gmail, crie uma senha de aplicativo na conta Google e configure:

```dotenv
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=seu-email@gmail.com
SMTP_PASSWORD=sua-senha-de-aplicativo
SMTP_AUTH=true
SMTP_STARTTLS=true
MAIL_FROM=seu-email@gmail.com
WEB_URL=https://seu-dominio.example
```

Nunca utilize a senha normal da conta nem envie o arquivo `.env` ao repositório.

## PostgreSQL local

- Host: `localhost`
- Porta: `5432`
- Usuário e senha padrão: `ciclo`
- Bancos: `ciclo_identity`, `ciclo_study`, `ciclo_ai` e `ciclo_admin`

```bash
psql -h localhost -p 5432 -U ciclo -d ciclo_identity
docker compose exec postgres psql -U ciclo -d ciclo_identity
```

## Qualidade

```bash
./scripts/format.sh
./scripts/test-backend.sh
corepack pnpm --dir frontend install --frozen-lockfile
corepack pnpm --dir frontend run format:check
corepack pnpm --dir frontend test
corepack pnpm --dir frontend run build:all
```

O script `format.sh` aplica Google Java Format via Spotless em todos os módulos Maven,
organiza os arquivos `pom.xml` e executa Prettier nos projetos Angular. O comando
`mvn spotless:check` valida a formatação Java sem alterar arquivos.

O frontend usa Angular e Tailwind CSS 4, com bibliotecas compartilhadas para UI, autenticação e cliente HTTP tipado.

Cada serviço segue os anéis `domain`, `application`, `infrastructure` e `presentation`. Os testes ArchUnit impedem dependências das camadas internas em Spring/JPA/adapters.
