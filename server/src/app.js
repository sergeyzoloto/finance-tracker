import express from 'express';
import cors from 'cors';

// Create an express server
const app = express();

// Tell express to use the json middleware
app.use(express.json()); // Set the payload limit to 50 megabytes
// Allow everyone to access our API. In a real application, we would need to restrict this!
app.use(cors());

export default app;
