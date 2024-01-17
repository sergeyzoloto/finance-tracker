import User, { validateUser } from '../models/User.js';
import { logError, logInfo } from '../util/logging.js';
import validationErrorMessage from '../util/validationErrorMessage.js';

// Load our .env variables
import dotenv from 'dotenv';
dotenv.config();
// Hashing passwords
import bcryptjs from 'bcryptjs';
const salt = bcryptjs.genSaltSync();
import jwt from 'jsonwebtoken';
const secret = process.env.JWT_SECRET;

/** GET USERS
 *
 * @route GET /api/user/
 * @desc Get all users
 */
export const getUsers = async (request, response) => {
  try {
    const users = await User.find();
    response.status(200).json({ success: true, result: users });
  } catch (error) {
    logError(error);
    response
      .status(500)
      .json({ success: false, message: 'Unable to get users' });
  }
};

/** CREATE USER
 *
 * @route POST /api/user/register/
 * @desc Create a new user with email and password
 */
export const createUser = async (request, response) => {
  try {
    const { user } = request.body;

    if (typeof user !== 'object') {
      response.status(400).json({
        success: false,
        message: `Provide a 'user' object. Received: ${JSON.stringify(user)}`,
      });

      return;
    }

    const errorList = validateUser(user);

    if (errorList.length > 0) {
      response.status(400).json({
        success: false,
        message: validationErrorMessage(errorList),
      });
    } else {
      const { email, password } = user;
      const hashedPassword = bcryptjs.hashSync(password, salt);

      const newUser = await User.create({
        email,
        password: hashedPassword,
      });
      response.status(201).json({
        success: true,
        user: newUser,
      });
    }
  } catch (error) {
    logError(error);
    response
      .status(500)
      .json({ success: false, message: 'Failed to create user' });
  }
};

/** LOG IN USER
 *
 * @route POST /api/user/login/
 * @desc Checks email and password and attaches token to cookies if valid
 */
export const login = async (request, response) => {
  try {
    const { user } = request.body;

    if (typeof user !== 'object') {
      response.status(400).json({
        success: false,
        message: `Provide a 'user' object. Received: ${JSON.stringify(user)}`,
      });

      return;
    }

    const errorList = validateUser(user);

    if (errorList.length > 0) {
      response.status(400).json({
        success: false,
        message: validationErrorMessage(errorList),
      });
    } else {
      const userInDB = await User.findOne({ email: user?.email });

      if (userInDB) {
        const passwordCheck = bcryptjs.compareSync(
          user.password,
          userInDB.password,
        );

        if (passwordCheck) {
          // logged in
          jwt.sign({ id: userInDB._id }, secret, {}, (error, token) => {
            if (error) throw error;
            response.status(200).cookie('token', token).json({
              success: true,
            });
          });
        } else {
          response.status(400).json({
            success: false,
            message: 'Wrong password',
          });
        }
      } else {
        response
          .status(404)
          .json({ success: false, message: 'User not found' });
      }
    }
  } catch (error) {
    logError(error);
    response
      .status(500)
      .json({ success: false, message: 'Failed to log in user' });
  }
};

/** LOG OUT USER
 *
 * @route GET /api/user/logout/
 * @desc Removes token from cookies and always response with success
 */
export const logout = async (request, response) => {
  try {
    response.status(200).cookie('token', '').json({
      success: true,
    });
  } catch (error) {
    logError(error);
    response
      .status(500)
      .json({ success: false, message: 'Failed to log out user' });
  }
};

/** GET USER PROFILE
 *
 * @route GET /api/user/profile/
 * @desc Checks token and returns user id
 */
export const getProfile = async (request, response) => {
  try {
    const { token } = request.cookies;

    if (token) {
      jwt.verify(token, secret, {}, (error, info) => {
        if (error) {
          response
            .status(498)
            .json({ success: false, message: 'Invalid token' });
        } else {
          response.status(200).json({
            success: true,
            user: info,
          });
        }
      });
    } else {
      response.status(499).json({
        success: false,
        message: 'Token is required but was not submitted ',
      });
    }
  } catch (error) {
    logError(error);
    response
      .status(500)
      .json({ success: false, message: 'Failed to get user profile' });
  }
};
