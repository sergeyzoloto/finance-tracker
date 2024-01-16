import app from './app.js';
import connectDB from './db/connectDB.js';
import { logInfo, logError } from './util/logging.js';

// Load our .env variables
import dotenv from 'dotenv';
dotenv.config();

// The environment should set the port
const port = process.env.PORT;

if (port == null) {
  // If this fails, make sure you have created a `.env` file in the right place with the PORT set
  logError(new Error('Cannot find a PORT number, did you create a .env file?'));
}

const startServer = async () => {
  try {
    await connectDB();
    const server = app.listen(port, () => {
      logInfo(`Server started on port ${port}`);
    });
  } catch (error) {
    logError(error);
  }
};

// Start the server
startServer();
