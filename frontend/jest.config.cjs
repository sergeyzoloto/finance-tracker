module.exports = {
  testEnvironment: "jsdom",
  setupFilesAfterEnv: ["./setupTests.ts"],
  moduleNameMapper: {
    "^.+\\.svg$": "jest-svg-transformer",
    "^.+\\.(css|less|scss)$": "identity-obj-proxy",
  },
};
