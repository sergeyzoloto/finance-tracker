/**
 * In this file we can create functions to mock results given by the backend
 */

// Mock of a successful getting of users
export const getUsersSuccessMock = (users = []) => {
  return JSON.stringify({ success: true, result: users });
};

// Mock of a successful getting of users
export const getUsersFailedMock = () => {
  return JSON.stringify({ success: false, message: 'Something went wrong' });
};

// Mock of a successful creation of a new user
export const createUserSuccessMock = (user = {}) => {
  return JSON.stringify({ success: true, user });
};

// Mock of a failing creation of a new user
export const createUserFailedMock = () => {
  return JSON.stringify({ success: false, message: 'Something went wrong' });
};

// Mock of a successful login
export const loginSuccessMock = (user = {}) => {
  return JSON.stringify({ success: true });
};

// Mock of a failing login
export const loginFailedMock = () => {
  return JSON.stringify({ success: false, message: 'Failed to log in' });
};
