import express from 'express';
import {
  createUser,
  getUsers,
  login,
  logout,
  getProfile,
} from '../controllers/user.js';

const userRouter = express.Router();

userRouter.get('/', getUsers);
userRouter.post('/register', createUser);
userRouter.post('/login', login);
userRouter.get('/logout', logout);
userRouter.get('/profile', getProfile);
// userRouter.put('/update', updateUser);
// userRouter.delete('/delete', deleteUser);

export default userRouter;
