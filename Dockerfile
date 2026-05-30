FROM node:22-alpine

WORKDIR /app

ENV NODE_ENV=production

COPY server/package.json ./package.json
COPY server/src ./src

EXPOSE 7000 40001 40002

USER node

CMD ["node", "src/cli.js"]
