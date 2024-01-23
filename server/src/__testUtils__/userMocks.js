import User, { validateUser } from '../models/User';

// Hashing passwords
import bcryptjs from 'bcryptjs';
const salt = bcryptjs.genSaltSync();

// Load our .env variables
import dotenv from 'dotenv';
dotenv.config();
const secret = process.env.JWT_SECRET;

import jwt from 'jsonwebtoken';

export const addUserToMockDB = async (newUser) => {
  const validationResult = validateUser(newUser);

  if (validationResult.length > 0) {
    throw new Error(
      `Invalid user attempting to be added to the Database. User attempted to be added: ${JSON.stringify(
        newUser,
      )}. Received errors: ${validationResult.join(', ')}.`,
    );
  }

  const { email, password } = newUser;
  const hashedPassword = bcryptjs.hashSync(password, salt);
  const user = new User({
    email,
    password: hashedPassword,
  });
  await user.save();

  const userId = user._id;
  return userId;
};

export const findUserInMockDB = async (userId) => {
  if (typeof userId !== 'string') {
    throw new Error(
      `Invalid userId given! Should be a string, but received: ${userId}`,
    );
  }

  const user = await User.findById(userId);

  return user;
};

export const verifyUserInMockDB = async (user, password) => {
  if (user == null) {
    throw new Error(`User object has to be attached`);
  }

  if (password == null) {
    throw new Error(`Password to compare must be provided`);
  }

  const validationResult = validateUser(user);

  if (validationResult.length > 0) {
    throw new Error(
      `Invalid user object: ${JSON.stringify(
        user,
      )}. Received errors: ${validationResult.join(', ')}.`,
    );
  }

  const passwordCheck = bcryptjs.compareSync(user?.password, password);

  if (passwordCheck) {
    return true;
  } else {
    return false;
  }
};

export const generateTokenInMockDB = (userId) => {
  return new Promise((resolve, reject) => {
    jwt.sign({ id: userId }, secret, {}, (error, token) => {
      if (error) {
        reject(error);
      } else {
        resolve(token);
      }
    });
  });
};
