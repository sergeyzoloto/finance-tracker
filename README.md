# finance-tracker

## web app using mern

Finance Tracker is a personal project of a web application for accounting financial transactions. The project was created using MERN (MongoDB, Express, React, Node.js) and applying TDD.

## 1. project structure

```
root
└── client
| ├── public
| └── src
|     └── __tests__
|     └── __testUtils__
|     └── components
|     └── context
|     └── hooks
|     └── layout
|     └── pages
|     |   └── Home
|     |   └── User
|     |       └── CreateUser
|     |       └── LoginPage
|     |       └── UserList
|     └── utils
|     App.jsx
|     AppWrapper.jsx
|     main.jsx
└── server
  └── src
      └── __tests__
      └── __testUtils__
      └── controllers
      └── db
      └── models
      └── routes
      └── util
      index.js
```

## 2. installation instructions

First, to setup all the directories run the following in the main directory:

`npm install`

`npm run setup`

The first command will install dependencies and some small libraries needed for running the rest of the commands. The second will go into the client and server directories and set those up to be ran.

In the client and server directory there are two .env.example files. Create a copy and rename that to .env. Then follow the instructions in those files to fill in the right values.

To run the app in dev mode you can run the following command in the main directory:

`npm run dev`

## 3.1 configuration libraries

- `dotenv` || To load the .env variables into the process environment. See [docs](https://www.npmjs.com/package/dotenv)
- `concurrently` || To run commands in parallel. See [docs](https://github.com/open-cli-tools/concurrently#readme)

## 3.2 server-side libraries

- `bcryptjs` ||
- `cookie-parser` ||
- `cors` ||
- `dotenv` ||
- `express` ||
- `jsonwebtoken` ||
- `mongoose` ||

- `@babel/preset-env` ||
- `babel-jest` ||
- `jest` ||
- `mongodb-memory-server` ||
- `nodemon` ||
- `supertest` ||

## 3.3 client-side libraries

- `react v18.2.0` ||
- `react-dom` ||
- `react-router-dom` ||

- `tailwindcss` ||
- `vite` ||
- `@babel/preset-env` ||
- `@babel/preset-react` ||
- `@testing-library/*` ||  We use React Testing Library to write all of our tests. See [docs](https://testing-library.com/docs/react-testing-library/intro/)
- `@types/react` ||
- `@types/react-dom` ||
- `@vitejs/plugin-react` ||
- `autoprefixer` ||
- `eslint` ||
- `eslint-plugin-react` ||
- `eslint-plugin-react-hooks` ||
- `eslint-plugin-react-refresh` ||
- `history` ||
- `identity-obj-proxy` ||
- `jest` || To run our tests and coverage. See [docs](https://jestjs.io/)
- `jest-environment-jsdom` ||
- `jest-fetch-mock` ||
- `jest-svg-transformer` ||
- `postcss` ||
- `react-test-renderer` ||
