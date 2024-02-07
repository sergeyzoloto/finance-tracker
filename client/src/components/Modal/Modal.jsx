import React from 'react';

const Modal = ({
  password,
  setPassword,
  handleConfirmDelete,
  handleModalClose,
}) => (
  <div className="fixed inset-0 flex items-center justify-center bg-black bg-opacity-50">
    <div className="bg-white p-4 rounded">
      <h2>Confirm user deletion</h2>
      <input
        type="password"
        value={password}
        onChange={(event) => setPassword(event.target.value)}
        className="mt-2 border rounded p-2"
        placeholder="Enter password"
      />
      <div className="mt-4 flex justify-end">
        <button
          onClick={handleConfirmDelete}
          className="mr-2 bg-blue-500 text-white rounded p-2"
        >
          Confirm
        </button>
        <button
          onClick={handleModalClose}
          className="bg-red-500 text-white rounded p-2"
        >
          Cancel
        </button>
      </div>
    </div>
  </div>
);

export default Modal;
