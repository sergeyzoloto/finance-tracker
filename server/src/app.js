import express from 'express';
import cors from 'cors';

// Load our .env variables
import dotenv from 'dotenv';
dotenv.config();

import userRouter from './routes/user.js';

// Create an express server
const app = express();

// Tell express to use the json middleware
app.use(express.json());

// CORS
const corsOptions = {
  origin: process.env.ORIGIN,
  credentials: true,
};

app.use(cors(corsOptions));

// Cookies
import cookieParser from 'cookie-parser';
app.use(cookieParser());

/****** Attach routes ******/
/**
 * We use /api/ at the start of every route!
 * As we also host our client code on heroku we want to separate the API endpoints.
 */
app.use('/api/user', userRouter);

export default app;
