import React from 'react';

const CredentialsInput = ({ ...rest }) => {
  return (
    <input
      {...rest}
      className="flexbox border-2 border-solid block w-full p-2 bg-white border-neutral-100 hover:border-neutral-200 text-center rounded max-w-sm"
    />
  );
};

export default CredentialsInput;
