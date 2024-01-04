import mongoose from 'mongoose';

import validateAllowedFields from '../util/validateAllowedFields.js';

const userSchema = new mongoose.Schema({
  password: { type: String, required: true, minLength: 7 },
  email: { type: String, required: true, unique: true },
});

const User = mongoose.model('users', userSchema);

export const validateUser = (userObject) => {
  const errorList = [];
  const allowedKeys = ['email', 'password'];

  const validatedKeysMessage = validateAllowedFields(userObject, allowedKeys);

  if (validatedKeysMessage.length > 0) {
    errorList.push(validatedKeysMessage);
  }

  const { email, password } = userObject;

  if (password == null) {
    errorList.push('password is a required field');
  }

  if (email == null) {
    errorList.push('email is a required field');
  }

  return errorList;
};

export default User;
