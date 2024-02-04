import createTestIdFilePath from '../../../utils/createTestIdFilePath';

const TEST_ID = {
  container: `${createTestIdFilePath('pages', 'User', 'LoginPage')}-container`,
  loadingContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'LoginPage',
  )}-loadingContainer`,
  errorContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'LoginPage',
  )}-errorContainer`,
  passwordInput: `${createTestIdFilePath(
    'pages',
    'User',
    'LoginPage',
  )}-passwordInput`,
  emailInput: `${createTestIdFilePath(
    'pages',
    'User',
    'LoginPage',
  )}-emailInput`,
  loginButton: `${createTestIdFilePath(
    'pages',
    'User',
    'LoginPage',
  )}-loginButton`,
  form: `${createTestIdFilePath('pages', 'User', 'LoginPage')}-form`,
};

export default TEST_ID;
