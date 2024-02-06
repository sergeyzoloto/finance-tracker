import createTestIdFilePath from '../../../utils/createTestIdFilePath';

const TEST_ID = {
  container: `${createTestIdFilePath('pages', 'User', 'UserList')}-container`,
  loadingContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
  )}-loadingContainer`,
  errorContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
  )}-errorContainer`,
  userList: `${createTestIdFilePath('pages', 'User', 'UserList')}-userList`,
  deleteUserButton: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
  )}-deleteUserButton`,
};

export default TEST_ID;
