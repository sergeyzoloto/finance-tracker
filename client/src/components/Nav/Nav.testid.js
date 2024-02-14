import createTestIdFilePath from '../../utils/createTestIdFilePath';

const TEST_ID = {
  linkToHome: `${createTestIdFilePath('components', 'Nav')}-linkToHome`,
  linkToUsers: `${createTestIdFilePath('components', 'Nav')}-linkToUser`,
  createUserLink: `${createTestIdFilePath('components', 'Nav')}-createUserLink`,
  loginLink: `${createTestIdFilePath('components', 'Nav')}-loginLink`,
};

export default TEST_ID;
