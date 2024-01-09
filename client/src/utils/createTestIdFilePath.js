/**
 * This file is used to create the TEST_ID file
 * paths
 */

function createTestIdFilePath(...args) {
  return args.join('/');
}

export default createTestIdFilePath;
