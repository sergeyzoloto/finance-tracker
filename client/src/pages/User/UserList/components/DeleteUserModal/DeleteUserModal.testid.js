import createTestIdFilePath from '../../../../../utils/createTestIdFilePath';

const TEST_ID = {
  container: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-container`,
  loadingContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-loadingContainer`,
  errorContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-errorContainer`,
  successContainer: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-successContainer`,
  userList: `${createTestIdFilePath('pages', 'User', 'UserList')}-userList`,
  cancelButton: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-cancelButton`,
  confirmButton: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-confirmButton`,
  passwordInput: `${createTestIdFilePath(
    'pages',
    'User',
    'UserList',
    'DeleteUserModal',
  )}-passwordInput`,
};

export default TEST_ID;
